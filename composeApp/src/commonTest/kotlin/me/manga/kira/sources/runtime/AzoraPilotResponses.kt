package me.manga.kira.sources.runtime

/** Unchanged canned responses shared by bundled-pilot and signed-activation engine tests. */
internal const val AZORA_QUERY_JSON = """
{
  "posts": [
    {
      "id": 92, "slug": "one-piece", "postTitle": "One Piece",
      "featuredImage": "https://api.azoramoon.com/covers/op.jpg",
      "seriesStatus": "ongoing", "totalViews": 1000, "author": "Oda", "averageRating": 8.5,
      "genres": [ { "id": 1, "name": "Action" }, { "id": 2, "name": "Adventure" } ],
      "chapters": [ { "id": 85027, "number": 1, "title": "Romance Dawn", "slug": "ch-1", "createdAt": "2024-01-15T12:00:00Z" } ]
    },
    {
      "id": 93, "slug": "naruto", "postTitle": "Naruto",
      "featuredImage": "https://api.azoramoon.com/covers/naruto.jpg",
      "seriesStatus": "completed", "totalViews": 900
    }
  ],
  "totalCount": 2
}
"""

internal const val AZORA_DETAILS_JSON = """
{
  "totalChapterCount": 2,
  "post": {
    "id": 92, "slug": "one-piece", "postTitle": "One Piece",
    "postContent": "<p>Pirates  &amp; adventure</p>",
    "featuredImage": "https://api.azoramoon.com/covers/op.jpg",
    "seriesStatus": "ongoing", "totalViews": 1000, "author": "Oda",
    "averageRating": 8, "totalRatings": 50,
    "genres": [ { "id": 1, "name": "Action" }, { "id": 2, "name": "Adventure" } ],
    "chapters": [
      { "id": 85027, "slug": "ch-1", "number": 1, "title": "Romance Dawn", "createdAt": "2024-01-15T12:00:00Z" },
      { "id": 85028, "slug": "ch-2", "number": 2, "title": null, "createdAt": "2024-01-22T12:00:00Z" },
      { "id": 85029, "slug": "ch-3", "number": 3.0, "title": null, "createdAt": "2024-02-01T12:00:00Z" }
    ]
  }
}
"""
