"""Semantic near-duplicate check for self-instruct-generated query examples,
scoped to keep comparisons cheap as the pool grows into the thousands:
persona first (comparing a Grazer-style mood query against a Critic/Analyst
query is never meaningful), then genre span if the candidate has one, and
only then embedding cosine similarity within that narrowed set - never a
brute-force compare against the whole accumulated pool.

Reuses the same embedding model as the real on-device retrieval path
(phase09_embed.py) so this isn't a second, disconnected embedding setup.
"""

import json

import numpy as np
from sentence_transformers import SentenceTransformer
from sklearn.cluster import KMeans

MODEL_NAME = "google/embeddinggemma-300m"
DEFAULT_THRESHOLD = 0.85


def _genre_of(spans: list[dict]) -> str | None:
    for s in spans:
        if s.get("type") == "genre":
            return s["span"].lower()
    return None


class SemanticDedup:
    def __init__(self, threshold: float = DEFAULT_THRESHOLD):
        self.model = SentenceTransformer(MODEL_NAME)
        self.threshold = threshold
        self.queries: list[str] = []
        self.personas: list[str] = []
        self.genres: list[str | None] = []
        self.embeddings: np.ndarray | None = None

    def load_existing(self, paths: list[str]) -> None:
        records = []
        for path in paths:
            for line in open(path):
                r = json.loads(line)
                persona = r.get("persona_context") or r.get("persona") or ""
                records.append((r["query"], persona, _genre_of(r.get("spans", []))))

        if not records:
            return
        queries = [r[0] for r in records]
        embs = self.model.encode(queries, normalize_embeddings=True, show_progress_bar=True)

        self.queries.extend(r[0] for r in records)
        self.personas.extend(r[1] for r in records)
        self.genres.extend(r[2] for r in records)
        self.embeddings = embs if self.embeddings is None else np.vstack([self.embeddings, embs])
        print(f"dedup pool loaded: {len(self.queries)} existing examples embedded")

    def _candidate_indices(self, persona: str, genre: str | None) -> list[int]:
        idx = [i for i, p in enumerate(self.personas) if p == persona]
        if genre:
            genre_idx = [i for i in idx if self.genres[i] and (genre in self.genres[i] or self.genres[i] in genre)]
            if genre_idx:
                return genre_idx
        return idx

    def check(self, query: str, persona: str, spans: list[dict]) -> tuple[bool, float, str | None]:
        """Returns (is_duplicate, max_similarity, most_similar_existing_query)."""
        if self.embeddings is None or len(self.queries) == 0:
            return False, 0.0, None
        genre = _genre_of(spans)
        candidates = self._candidate_indices(persona, genre)
        if not candidates:
            return False, 0.0, None

        query_emb = self.model.encode([query], normalize_embeddings=True)[0]
        cand_embs = self.embeddings[candidates]
        sims = cand_embs @ query_emb
        best = int(sims.argmax())
        best_sim = float(sims[best])
        return best_sim > self.threshold, best_sim, self.queries[candidates[best]]

    def add(self, query: str, persona: str, spans: list[dict]) -> None:
        genre = _genre_of(spans)
        emb = self.model.encode([query], normalize_embeddings=True)[0].reshape(1, -1)
        self.queries.append(query)
        self.personas.append(persona)
        self.genres.append(genre)
        self.embeddings = emb if self.embeddings is None else np.vstack([self.embeddings, emb])

    def cluster_near_duplicates(self, persona: str, n_clusters: int = 15, threshold: float | None = None) -> list[tuple[float, str, str]]:
        """k-means-scoped near-dup audit (NVIDIA NeMo Curator's approach):
        cluster a persona's pool, then only compare pairs within the same
        cluster - bounds comparison cost instead of full O(n^2) against the
        whole persona subset. Returns (similarity, query_a, query_b) triples
        above threshold, most-similar first."""
        threshold = threshold if threshold is not None else self.threshold
        idx = [i for i, p in enumerate(self.personas) if p == persona]
        if len(idx) < n_clusters * 2:
            n_clusters = max(1, len(idx) // 2)
        if n_clusters < 1 or len(idx) < 2:
            return []

        embs = self.embeddings[idx]
        labels = KMeans(n_clusters=n_clusters, n_init=10, random_state=0).fit_predict(embs)

        pairs = []
        for c in range(n_clusters):
            members = [idx[i] for i in range(len(idx)) if labels[i] == c]
            if len(members) < 2:
                continue
            cluster_embs = self.embeddings[members]
            sims = cluster_embs @ cluster_embs.T
            for a in range(len(members)):
                for b in range(a + 1, len(members)):
                    if sims[a, b] > threshold:
                        pairs.append((float(sims[a, b]), self.queries[members[a]], self.queries[members[b]]))
        pairs.sort(reverse=True)
        return pairs

    def farthest_from_centroid_examples(
        self, persona: str, n_clusters: int = 10, used_examples: set[str] | None = None,
        cross_iteration_threshold: float = 0.80,
    ) -> list[str]:
        """Steering examples for the next generation batch, NeMo Curator-style:
        cluster a persona's pool, take the example farthest from each
        cluster's centroid (atypical, not the over-represented centroid
        phrasing), then drop any that are too similar to an example already
        used as a reference in a previous batch (`used_examples`), so the
        steering set itself doesn't go stale across iterations."""
        used_examples = used_examples or set()
        idx = [i for i, p in enumerate(self.personas) if p == persona]
        if len(idx) < n_clusters:
            n_clusters = max(1, len(idx))
        if n_clusters < 1:
            return []

        embs = self.embeddings[idx]
        km = KMeans(n_clusters=n_clusters, n_init=10, random_state=0).fit(embs)
        labels = km.labels_
        centroids = km.cluster_centers_

        candidates = []
        for c in range(n_clusters):
            members = [idx[i] for i in range(len(idx)) if labels[i] == c]
            if not members:
                continue
            member_embs = self.embeddings[members]
            dists = np.linalg.norm(member_embs - centroids[c], axis=1)
            farthest = members[int(dists.argmax())]
            candidates.append(farthest)

        if used_examples:
            used_embs = self.model.encode(list(used_examples), normalize_embeddings=True)
            kept = []
            for c in candidates:
                sims = used_embs @ self.embeddings[c]
                if sims.max() < cross_iteration_threshold:
                    kept.append(c)
            candidates = kept

        return [self.queries[c] for c in candidates]
