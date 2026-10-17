package me.manga.kira.sources.runtime

// ---- Demonicscans real captured HTML ----
private const val DS_HOME = """
<html><body><div id="updates-container">
<div class="updates-element border-box"><div class="flex flex-row">
  <div class="thumb"><a href="/manga/Mr-Devourer%252C-Please-Act-Like-a-Final-Boss" title="Mr Devourer, Please Act Like a Final Boss"><img src="https://readermc.org/images/thumbnails/Mr Devourer, Please Act Like a Final Boss.webp"/></a></div>
  <div class="updates-element-info ml flex flex-col justify-space-between full-width">
    <h2 style="font-size:16px;"><a href="/manga/Mr-Devourer%252C-Please-Act-Like-a-Final-Boss" title="Mr Devourer, Please Act Like a Final Boss">Mr Devourer, Please Act Like a Final ...</a></h2>
    <div>
      <div class="flex flex-row chap-date justify-space-between"><div><a class="chplinks" href="chaptered.php?manga=12094&chapter=129">Chapter 129</a></div><div><a class="chplinks" href="chaptered.php?manga=12094&chapter=129">2026-06-05</a></div></div>
      <div class="flex flex-row chap-date justify-space-between"><div><a class="chplinks" href="chaptered.php?manga=12094&chapter=128">Chapter 128</a></div><div><a class="chplinks" href="chaptered.php?manga=12094&chapter=128">2026-05-29</a></div></div>
    </div></div></div></div>
</div></body></html>
"""

private const val DS_SEARCH = """
<html><body>
<a href="/manga/Solo-Leveling"><li class="flex flex-row">
  <img src="https://readermc.org/images/thumbnails/Solo Leveling.webp" class="search-thumb"/>
  <div class="flex flex-col seach-right justify-space-between"><div>Solo Leveling</div><div style="font-size:12px;">8942671</div></div>
</li></a>
</body></html>
"""

private const val DS_DETAILS = """
<html><body>
<div id="manga-page"><img src="https://readermc.org/images/thumbnails/Solo Leveling.webp"/></div>
<div id="manga-info-rightColumn"><h1>Solo Leveling</h1><div class="white-font">10 years ago, after the Gate opened...</div></div>
<div id="R-V-B"><span class="RVB">9.85</span><span class="RVB">8.49m</span><span class="RVB">4.06k</span></div>
<div id="manga-info-stats">
  <div class="flex flex-row"><li style="width:150px;">Author</li><li>Chugong,H-goon,KI Soryeong</li></div>
  <div class="flex flex-row"><li>Rating</li><li>100%</li></div>
  <div class="flex flex-row"><li>Status</li><li>Completed</li></div>
  <div class="flex flex-row"><li>Last Update</li><li>2024-07-13</li></div>
</div>
<ul class="genres-list"><li>Shounen</li><li>Fantasy</li><li>Action</li></ul>
<ul id="chapters-list">
  <li><a class="chplinks" href="/chaptered.php?manga=6&chapter=200.5" title="Solo Leveling 200.5">Chapter 200.5<span style="float:right;text-align: right;">2024-07-13</span></a></li>
  <li><a class="chplinks" href="/chaptered.php?manga=6&chapter=200" title="Solo Leveling 200">Chapter 200<span style="float:right;text-align: right;">2024-07-06</span></a></li>
</ul>
</body></html>
"""

private const val DS_FEATURED = """
<html><body><div id="carousel">
<div class="owl-element"><a href="/manga/Reformation-Of-The-Deadbeat-Noble" title="Reformation Of The Deadbeat Noble"><img src="https://readermc.org/images/thumbnails/The lazy prince becomes a genius.webp" alt="Reformation Of The Deadbeat Noble"/><div class="shadowimg"></div><h1>Reformation Of The Deadbeat Noble<br/><div>5090054</div></h1></a></div>
</div></body></html>
"""

internal const val DS_PAGES = """
<html><body>
<img class="imgholder" src="/img/free_ads.jpg"/>
<img class="imgholder" src="https://mangareadon.org/Solo Leveling/1/1.jpg"/>
<img class="imgholder" src="https://mangareadon.org/Solo Leveling/1/2.jpg"/>
</body></html>
"""

// ---- Mangabuddy / mangak.io real API shapes ----
// /titles/home serves both home (data.latest.items — an {items,pagination} object) and featured
// (data.popular — a BARE LIST, matching the live API; it is NOT wrapped in {items:[...]}).
private const val MB_HOME = """
{ "data": {
  "latest":  { "items": [ { "id": "p2qOKyBY", "name": "Shoot Through a Different World", "cover": "https://rx.resmk.org/covers/10.webp", "genres": [ { "name": "Action" } ] } ] },
  "popular": [ { "id": "VYPXkPYz", "name": "Naruto", "cover": "https://rx.resmk.org/covers/nar.webp", "genres": [ { "name": "Action" } ] } ]
} }
"""

private const val MB_SEARCH = """
{ "data": { "items": [ { "id": "VYPXkPYz", "name": "Naruto", "cover": "https://rx.resmk.org/covers/nar.webp", "genres": [ { "name": "Action" } ] } ] } }
"""

private const val MB_DETAILS = """
{ "data": { "title": { "name": "Naruto", "cover": "https://rx.resmk.org/covers/nar.webp", "status": "Completed",
  "summary": "Ninja story.", "genres": [ { "name": "Action" }, { "name": "Adventure" } ] } } }
"""

private const val MB_CHAPTERS = """
{ "data": { "chapters": [
  { "id": "WYXlbzbY", "url": "/naruto/chapter-700-5", "name": "Chapter 700.5", "chapter_number": 748, "updated_at": "2019-08-25T02:08:00.000Z" },
  { "id": "abc", "url": "/naruto/chapter-700", "name": "Chapter 700", "chapter_number": 747, "updated_at": "2019-08-20T00:00:00.000Z" }
] } }
"""

private const val MB_READER = """
<html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"initialChapter":{"images":["https://rx.qvzrd.org/a.webp","https://rx.qvzrd.org/b.webp"]}}}}</script></body></html>
"""

// ---- Zazamanga real captured HTML (Madara) ----
private const val ZZ_HOME = """
<html><body>
<div class="page-item-detail manga">
  <div class="item-thumb hover-details" data-post-id="75718"><a href="https://www.zazamanga.com/manga/the-berserkers-second-playthrough" title="Read The Berserker’s Second Playthrough"><img alt="The Berserker’s Second Playthrough" class="img-responsive" src="https://cdn4.zinmanga1.com/thumb/the-berserkers-second-playthrough.webp"/></a></div>
  <div class="item-summary">
    <div><div class="post-title font-title"><h3 class="h5"><a href="https://www.zazamanga.com/manga/the-berserkers-second-playthrough">The Berserker’s Second Playthrough</a></h3></div></div>
    <div class="tags"><span><a href="/manga-genre/action" rel="tag">Action</a></span><span><a href="/manga-genre/adventure" rel="tag">Adventure</a></span><span><a href="/manga-genre/drama" rel="tag">Drama</a></span><span><a href="/manga-genre/fantasy" rel="tag">Fantasy</a></span><span><a href="/manga-genre/seinen" rel="tag">Seinen</a></span></div>
    <div class="list-chapter"><div class="chapter"><div class="chapter-detail"><a class="btn-link" href="/manga/the-berserkers-second-playthrough/chapter-30"> Chapter 30 </a></div><span class="post-on">50 minutes ago</span></div></div>
  </div>
</div>
</body></html>
"""

private const val ZZ_DETAILS = """
<html><body>
<div class="summary_image"><img class="img-comic effect-fade" data-backup="https://cdn4.zinmanga1.com/thumb/the-berserkers-second-playthrough.webp" src="/image/dflazy.jpg"/></div>
<h1 class="post-title font-title">The Berserker’s Second Playthrough</h1>
<div class="post-content"><div class="tags"><a href="/manga-genre/action" rel="tag">Action</a><a href="/manga-genre/adventure" rel="tag">Adventure</a><a href="/manga-genre/drama" rel="tag">Drama</a><a href="/manga-genre/fantasy" rel="tag">Fantasy</a><a href="/manga-genre/seinen" rel="tag">Seinen</a></div></div>
<span id="averagerate">3.5</span>
<div class="description-summary"><div class="summary__content">About The Berserker’s Second Playthrough story.</div></div>
<ul>
<li class="wp-manga-chapter"><a href="https://www.zazamanga.com/manga/the-berserkers-second-playthrough/chapter-30">Chapter 30</a><span class="chapter-release-date"><i>51 minutes ago</i></span></li>
<li class="wp-manga-chapter"><a href="https://www.zazamanga.com/manga/the-berserkers-second-playthrough/chapter-29">Chapter 29</a><span class="chapter-release-date"><i>1 week ago</i></span></li>
</ul>
</body></html>
"""

private const val ZZ_PAGES = """
<html><body>
<img id="manga-image-0" class="wp-manga-chapter-img effect-fade" src="https://cdn4.zinmanga1.com/the-berserkers-second-playthrough/30/0.webp"/>
<img id="manga-image-1" class="wp-manga-chapter-img effect-fade" src="https://cdn4.zinmanga1.com/the-berserkers-second-playthrough/30/1.webp"/>
</body></html>
"""

// Zazamanga page-2 home with a blacklisted ("Yaoi") item + a clean item — verifies blacklistGenres filtering.
private const val ZZ_HOME_BL = """
<html><body>
<div class="page-item-detail manga">
  <div class="item-thumb"><a href="https://www.zazamanga.com/manga/blacklisted-one" title="x"><img src="https://cdn4.zinmanga1.com/thumb/blacklisted.webp"/></a></div>
  <div class="item-summary"><div><div class="post-title font-title"><h3 class="h5"><a href="https://www.zazamanga.com/manga/blacklisted-one">Blacklisted One</a></h3></div></div>
    <div class="tags"><span><a href="/manga-genre/romance" rel="tag">Romance</a></span><span><a href="/manga-genre/yaoi" rel="tag">Yaoi</a></span></div></div>
</div>
<div class="page-item-detail manga">
  <div class="item-thumb"><a href="https://www.zazamanga.com/manga/clean-series" title="x"><img src="https://cdn4.zinmanga1.com/thumb/clean.webp"/></a></div>
  <div class="item-summary"><div><div class="post-title font-title"><h3 class="h5"><a href="https://www.zazamanga.com/manga/clean-series">Clean Series</a></h3></div></div>
    <div class="tags"><span><a href="/manga-genre/action" rel="tag">Action</a></span></div></div>
</div>
</body></html>
"""

internal val EN_RESPONSES: Map<String, String> = mapOf(
    "https://www.zazamanga.com/manga?orderby=latest&page=2" to ZZ_HOME_BL,
    "https://demonicscans.org/lastupdates.php?list=1" to DS_HOME,
    "https://demonicscans.org/search.php?manga=solo" to DS_SEARCH,
    "https://demonicscans.org/manga/Solo-Leveling" to DS_DETAILS,
    "https://demonicscans.org/" to DS_FEATURED,
    "https://demonicscans.org/chaptered.php?manga=6&chapter=1" to DS_PAGES,
    "https://api.mangak.io/titles/home" to MB_HOME,
    "https://api.mangak.io/titles/search?q=naruto" to MB_SEARCH,
    "https://api.mangak.io/titles/VYPXkPYz" to MB_DETAILS,
    "https://api.mangak.io/titles/VYPXkPYz/chapters" to MB_CHAPTERS,
    "https://mangak.io/naruto/chapter-700-5" to MB_READER,
    "https://www.zazamanga.com/manga?orderby=latest&page=1" to ZZ_HOME,
    "https://www.zazamanga.com/manga/the-berserkers-second-playthrough" to ZZ_DETAILS,
    "https://www.zazamanga.com/manga/the-berserkers-second-playthrough/chapter-30" to ZZ_PAGES,
)
