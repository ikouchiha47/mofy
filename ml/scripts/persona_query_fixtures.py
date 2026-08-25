"""Per-persona test queries for probing the Gemma 3 270M multi-aspect
decomposer (ADR 0008). Not all personas interact with free-text query
decomposition - Resumer, Archivist, and pure-sync Guest are action-driven,
not search-driven, so they're marked out of scope here rather than given
fabricated queries. See persona.md for full persona descriptions.
"""

PERSONA_QUERIES = {
    "Hunter": [
        "the dark knight",
        "download inception 2010",
    ],
    "Grazer": [
        "feeling nostalgic",
        "something thrilling and tense",
        "90s highly rated action movies",
        "I am feeling quite bored",
    ],
    "Resumer": [],  # out of scope - continue-watching row + notifications, no free-text query
    "Archivist": [],  # out of scope - directory picker + TMDB fuzzy match, no free-text query
    "Guest": [
        "something we can all watch tonight, nothing too violent",
    ],
    "Binger": [
        "what's new in the shows I'm following",
    ],
    "Completionist": [
        "show me the full MCU in order",
        "all of Miyazaki's films",
    ],
    # New personas from this session's architecture discussion:
    "Critic/Analyst": [
        "movies like Inception but more mind-bending",
        "tell me about the cast and behind-the-scenes trivia for Oppenheimer",
    ],
    "Social Planner": [
        "something we can all watch, my partner likes comedy but I want drama",
        "a movie that works for a mixed group, nothing too niche",
    ],
    "Discovery Junkie": [
        "surprise me with something I've never heard of",
        "something completely off my usual radar",
    ],
    "Re-watcher": [
        "something comforting I've seen a hundred times",
        "put on my usual comfort movie",
        "the one I always go back to when I can't decide",
    ],
}
