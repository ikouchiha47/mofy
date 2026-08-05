# Mofy

This is a thing to download movies from torrent websites.

**THIS IS FOR PERSONAL USE ONLY**

Usual workflow is like:

- Have a list of where one can add website. Start with https://yts.vg/
- The list will be divided into TV and Movie
- Have a webview which will open the website
- Block all ad overlay links, something like brave.
- Get the html and convert to text, or check the url as well
- Use something like MobileBert to get the movie name, or music album name.
- For the initial version, we can have a mapping on where to get the movie name from:
    - for yts its, `document.querySelector(".right-details-box .title-year h1").textContent`

- Once the user clicks on the magnet link, the download should happen inside the
  app, using some form of webtorrent or torrent like libraries.
- We need to then fetch the details regarding the movie/tv show from tmdb.
- Once we have the movie, we show it in a netflix like interface, where user can
  like and dislike.
- We will have a recommendation system. There are two ways to do this, either do
  it from the any imdb listing database or only from local. Its better to do it
  from the imdb listings.
- Also, its not like, user needs to download the movie from the torrent or the
in-app downloader, for mvp, one can download it from anywhere, and import it.
- It should be possible to pull in a directory with movie and srt files or just
  the movie. Then the user can fillup the name of the movie. Then trigger a
autofill, which will use the tmdb api or imdb dataset to get the genres,
overview and shit. That way we are not tied to the app.
- Basically the app can respond to magent:// or open torrent file with an app
- If the user has uTorrent or similar apps installed, they can show up in the
share sheet
- For playing the movie, one can use mpv like tools
- Important: while a movie/show is playing, the app should not get
  backgrounded/killed by the OS. Keep the screen awake and the player
  process alive (like how video call apps or other video players stay
  foregrounded), otherwise playback gets interrupted or position tracking
  breaks.
- The homepage, should show movies/shows that the user was watching.
- User can also remove an item from the list, it should signal a not-interested
  or dislike
- Similar to netflix, there should be a like, double like, and not-interested

### Tmdb API

- .env has a related .env.prod which contains the actual `TMDB_API_KEY`.
- Only the .env is comitted.
- Two apis are important
  - TV or Movie Search
  - TV and Movie Genre Listing (can be saved locally)

#### Search API

```javascript
xhr.open("GET", "https://api.themoviedb.org/3/search/movie?query=Jack%20Reacher");
xhr.setRequestHeader("Authorization", `Bearer ${tmdb_api_key}`);
xhr.setRequestHeader("Accept", "application/json");
```

Response:

```json
{
	"page": 1,
	"results": [
		{
			"adult": false,
			"backdrop_path": "/iwvP8XVpYVmJ3xfF9xdBi5uAOWl.jpg",
			"genre_ids": [
				80,
				18,
				53,
				28
			],
			"id": 75780,
			"title": "Jack Reacher",
			"original_language": "en",
			"original_title": "Jack Reacher",
			"overview": "One morning in an ordinary town, five people are shot dead in a seemingly random attack. All evidence points to a single suspect: an ex-military sniper who is quickly brought into custody. The interrogation yields one written note: 'Get Jack Reacher!'. Reacher, an enigmatic ex-Army investigator, believes the authorities have the right man but agrees to help the sniper's defense attorney. However, the more Reacher delves into the case, the less clear-cut it appears. So begins an extraordinary chase for the truth, pitting Jack Reacher against an unexpected enemy, with a skill for violence and a secret to keep.",
			"popularity": 19.0918,
			"poster_path": "/uQBbjrLVsUibWxNDGA4Czzo8lwz.jpg",
			"release_date": "2012-12-20",
			"softcore": false,
			"video": false,
			"vote_average": 6.674,
			"vote_count": 7753
		},
		{
			"adult": false,
			"backdrop_path": "/ww1eIoywghjoMzRLRIcbJLuKnJH.jpg",
			"genre_ids": [
				28,
				53,
				18,
				80
			],
			"id": 343611,
			"title": "Jack Reacher: Never Go Back",
			"original_language": "en",
			"original_title": "Jack Reacher: Never Go Back",
			"overview": "Years after resigning command of an elite military police unit, the nomadic, righter-of-wrongs Reacher is drawn back into the life he left behind when his friend and successor, Major Susan Turner is framed for espionage. Reacher will stop at nothing to prove her innocence and to expose the real perpetrators behind the killings of his former soldiers.",
			"popularity": 13.3785,
			"poster_path": "/cOg3UT2NYWHZxp41vpxAnVCOC4M.jpg",
			"release_date": "2016-10-19",
			"softcore": false,
			"video": false,
			"vote_average": 6.029,
			"vote_count": 5418
		},
		{
			"adult": false,
			"backdrop_path": null,
			"genre_ids": [
				99
			],
			"id": 1045592,
			"title": "Jack Reacher: When the Man Comes Around",
			"original_language": "en",
			"original_title": "Jack Reacher: When the Man Comes Around",
			"overview": "Cast and crew speak on adapting One Shot as the first Jack Reacher film, casting Tom Cruise, earning Lee Child's blessing, additional character qualities and the performances that shape them, Lee Child's cameo in the film, and shooting the film's climax.",
			"popularity": 1.0823,
			"poster_path": "/tcOPca5Ook6aR9mehrnxD9kfk7m.jpg",
			"release_date": "2013-05-07",
			"softcore": false,
			"video": false,
			"vote_average": 7.4,
			"vote_count": 6
		}
	],
	"total_pages": 1,
	"total_results": 3
}
```

for tv, its `/search/tv`

#### Genre Listing

```js
hr.open("GET", "https://api.themoviedb.org/3/genre/movie/list");
xhr.setRequestHeader("Authorization", `Bearer ${tmdb_api_key}`);
xhr.setRequestHeader("Accept", "application/json");
```

for tv, its `/genre/tv/list`

Response:

```json
{
	"genres": [
		{
			"id": 28,
			"name": "Action"
		},
		{
			"id": 12,
			"name": "Adventure"
		}
  ]
}
```

Trimmed out for example.

#### Discover movies/tv

```sh
curl --request GET \
  --url 'https://api.themoviedb.org/3/discover/movie?include_adult=false&include_video=false&language=en-US&page=1&sort_by=popularity.desc&with_genres=' \
  --header 'accept: application/json' \
  --header 'Authorization: Bearer $tmdb_api_key'
```

```txt
with_genres (string)

can be a comma (AND) or pipe (OR) separated query
```

For tv its: `/discover/tv?`

#### Search By Imdb ID

```js
xhr.open("GET", "https://api.themoviedb.org/3/find/tt0790724?external_source=imdb_id&language=en-US");
xhr.setRequestHeader("accept", "application/json");
xhr.setRequestHeader("Authorization", `Bearer ${tmdb_api_key}`);
```

Response:

```json
{
	"movie_results": [
		{
			"adult": false,
			"backdrop_path": "/iwvP8XVpYVmJ3xfF9xdBi5uAOWl.jpg",
			"id": 75780,
			"title": "Jack Reacher",
			"original_title": "Jack Reacher",
			"overview": "One morning in an ordinary town, five people are shot dead in a seemingly random attack. All evidence points to a single suspect: an ex-military sniper who is quickly brought into custody. The interrogation yields one written note: 'Get Jack Reacher!'. Reacher, an enigmatic ex-Army investigator, believes the authorities have the right man but agrees to help the sniper's defense attorney. However, the more Reacher delves into the case, the less clear-cut it appears. So begins an extraordinary chase for the truth, pitting Jack Reacher against an unexpected enemy, with a skill for violence and a secret to keep.",
			"poster_path": "/uQBbjrLVsUibWxNDGA4Czzo8lwz.jpg",
			"media_type": "movie",
			"original_language": "en",
			"genre_ids": [
				80,
				18,
				53,
				28
			],
			"popularity": 19.0918,
			"release_date": "2012-12-20",
			"softcore": false,
			"video": false,
			"vote_average": 6.674,
			"vote_count": 7754
		}
	],
	"person_results": [],
	"tv_results": [],
	"tv_episode_results": [],
	"tv_season_results": []
}
```

### Recommendation

- One way is we match using the `genres` and sentiment analysis of `overview`, vector embedding.
- Second which I want is, I want to be recommended movies based on my mood like
  query, for ex: I am feeling quite bored. or I am feeling loved or Suggest some
  thrilling movies.

  These can output multiple genres, like thriller can generate: horror, action,
  overview embedding search.
- Mood also covers nostalgia, like "feeling nostalgic" should surface stuff
  from a particular era/childhood-adjacent picks, not just genre.
- Search should also work off overview/plot alone, for when I only remember
  what happened in a movie, not its name or genre. E.g. "a guy loses his
  memory every day and writes notes to himself" -> Memento. This is really
  just the overview embedding search applied to a raw query instead of a
  mood label.
- Given my selection, it can either call the discovery api in tmdb, or use the
  imdb dataset.

- It can sort the results by relevancy. rating is not the only score, its a RRF.
- For each item, there will be a copy icon, so that user can easily copy it
- The like, double like, not interested or watched midway and never watched
again, or removed from the watch list, should feedback as signal for the
recommender

These results dont need to be dependent on wether its on torrent or whatever.
User can open the pages in webview and manually search and see if its there.

### Watch Together

Sometimes I want to watch the same movie/show with my girlfriend while she's
in another room (or another device), without both of us huddled around one
screen.

- I share a link/room code with her. We do NOT stream the movie file between
  devices - each person already has (or downloads) their own copy of the
  movie from wherever, on their own device. This is much cheaper for MVP,
  since we never have to move video bytes across the network.
- We assume the movie name/identifier resolves to the same thing on both
  sides, so it can hash to the same value (e.g. normalized title + year, or
  the tmdb id once matched). That hash is what the room uses to know we're
  both talking about the same movie, without needing to compare files.
- Once both people have added "their" copy of the movie to the same room,
  the only thing that needs to sync is playback state: play, pause, seek
  position, and forward/backward. No frames, no file transfer at all.
- This means sync is just small messages over local network (or even over
  internet later if not in the same house) - a websocket carrying
  `{event: play/pause/seek, position: 12:34}` is enough.
- If devices drift out of sync (buffering, slightly different file cuts
  etc), host should periodically
  broadcast its current position so the other device can auto-correct.

### Notifications

User can watch movies and leave midway. The app should track that, and show an
notification if the user wants to resume.

When to send the notification, track the local time, and send it 4-5 minutes
before the same time.

App can also track the user watch patterns, if the user only watches on certain
days, then the notification should adjust to that.

