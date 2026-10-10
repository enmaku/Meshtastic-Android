# What USGS Topo and USGS Imagery actually serve

Measured on 10 October 2026 (UTC) against the two tile services this app already calls. Primary sources are USGS pages, the live MapServer and WMTS documents, and a small sample of tile responses. This is not a download of a region.

- USGS Topo: `https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}`
- USGS Imagery: `https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}`

`{z}/{y}/{x}` is the ArcGIS order `{level}/{row}/{column}`, row 0 at the north. A level-16 request for downtown Washington, DC returned the expected streets (18th St NW and M St NW) and aerial photo, so that order matches these servers.

## Short answer

USGS says map services and downloaded National Map data are free and in the public domain, with no restrictions other than a requested acknowledgment. The USGS pages below do not state a request rate for these tile URLs. The live MapServers do allow an offline tile export, and they cap it at 100,000 tiles.

A tile the cache does not have is HTTP 404 with an HTML error page, including outside the detailed coverage and at levels 17 and 19 (both services list those levels, and neither served them). It is not a blank image and it does not send Esri's `blank-tile` header. Some places that look empty still have a real cached tile: open water on Topo is a flat blue JPEG of 2,421 bytes, and open water on Imagery at detailed zooms is a fully transparent PNG of 872 bytes.

Docs do not state a byte size. In this sample, JPEG/PNG sizes (no extra HTTP compression) were about 5–47 KB for Topo land and about 9–38 KB for Imagery land across zooms 0–16. The world tiles at zooms 0 and 1 are the outliers: Topo is a PNG of about 95–97 KB. The zooms that dominate a region's tile count (12–16) were about 5–39 KB (Topo) and 14–38 KB (Imagery) on land. A single constant per tile would mis-state both water and sparse desert.

## Bulk download, offline copies, and rate limits

**Use of the services.** USGS says map services and data downloaded from The National Map are free and in the public domain. "There are no restrictions," with a request that products derived from the services include: "Map services and data available from U.S. Geological Survey, National Geospatial Program." The same answer for downloaded data asks for "Data available from U.S. Geological Survey, National Geospatial Program."

- https://www.usgs.gov/faqs/what-are-terms-uselicensing-map-services-and-data-national-map
- https://www.usgs.gov/faqs/are-there-any-costs-or-restrictions-usage-data-downloaded-national-map

**No published rate limit for these URLs.** Those two FAQs, the tiled-service FAQ, the basemap FAQ, the imagery-service FAQ, and the TNMAccess FAQ do not give a requests-per-second cap, a daily quota, or a fair-use number for `USGSTopo` or `USGSImageryOnly`. Observed responses carried `Cache-Control: max-age=86400` and were served from CloudFront (`x-cache: Hit from cloudfront` on tiles that exist). That is cache lifetime, not a rate policy.

**Bulk download of National Map products is a different path from these tile URLs.** USGS says The National Map has one API, TNMAccess, for the downloadable products in The National Map Download Client. Vector downloads in bulk are only by special request to `tnm_help@usgs.gov`. That vector sentence is about vector products, not about these basemap tiles. Orthoimagery products are downloaded from EarthExplorer as GeoTIFF or JPEG2000, not by harvesting this tile cache.

- https://www.usgs.gov/faqs/there-api-accessing-national-map-data
- https://www.usgs.gov/faqs/how-can-i-download-vector-data-national-map-bulk
- https://www.usgs.gov/faqs/what-are-urls-imagery-services-national-map-and-are-they-cached-or-dynamic

**What the Imagery service itself says about downloads.** The live `USGSImageryOnly` description says the Download Client offers free public-domain 1-meter orthoimagery as JPEG 2000 for the conterminous United States, and that the 10-meter Alaska orthoimagery "will not be available for direct download from the National Map due to license restrictions." The same text says Alaska 10-meter SPOT imagery "is provided for viewing." Anchorage tiles at levels 8, 12, 14, and 16 were ordinary JPEGs (about 14–25 KB), so the viewing cache serves Alaska. The license line is about direct download of that source dataset.

- https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer?f=pjson

**US Topo quadrangles are a different product.** USGS says topographic maps are public domain except three cases that apply only to US Topo maps (2009–present): most 2010–2016 maps contain commercially licensed road data, Alaska orthoimages are commercially licensed, and Hawaii orthoimages were commercially licensed until 2016. Those notices belong on the GeoPDF/GeoTIFF quadrangles. The live tile-service `copyrightText` does not repeat them. Topo cites National Map themes plus Natural Earth, Census TIGER/Line, USFS roads, State HIU, and NOAA, refreshed 4 September 2026. Imagery cites "USDA, USGS The National Map: Orthoimagery," refreshed June 2024.

- https://www.usgs.gov/faqs/are-usgs-topographic-maps-copyrighted
- https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer?f=pjson

**Offline copies of the tile cache.** Both MapServers publish:

- `exportTilesAllowed`: `true`
- `maxExportTilesCount`: `100000`
- `singleFusedMapCache`: `true`
- `storageInfo.storageFormat`: `esriMapCacheStorageModeCompactV2`

Esri's Map Service reference defines those two export fields as whether clients can export cache tiles, and the maximum number that can be exported to a cache dataset or a tile package. Esri's Export Tiles operation "allows client applications to download map tiles from a server for offline use." The result is a tile package (`.tpk` / `.tpkx`) or a cache raster dataset. The documented default maximum is 100,000, which is the value on these two services. If `exportExtent` is omitted, Esri says the default extent is the full extent of the tiled map service. On both of these services that extent is essentially worldwide Web Mercator, not the United States (`fullExtent` spans about ±20,037,508 m in x and about ±30,242,455 m in y). An export without an extent is a request for the whole service, then truncated at 100,000 tiles.

- https://developers.arcgis.com/rest/services-reference/enterprise/map-service/
- https://developers.arcgis.com/rest/services-reference/enterprise/export-tiles-map-service/

Esri also documents an optional server-admin throttle, `maximumOutstandingJobs` on the CachingControllers service, which can return HTTP 503 and `Retry-After: 300` when too many export jobs are queued. That property is not in the public MapServer JSON, so this note does not claim USGS has set it.

## What a request returns when the cache has no tile

The service extent does not clip to the United States. Coverage holes show up as missing tiles, not as a smaller `fullExtent`.

Esri's Map Tile reference says the image bytes are streamed, and "If the tile is not found, an HTTP status code of 404 (Not found) is returned." It also defines a `blankTile` parameter for caches configured to return a blank image: when the parameter is omitted, a blank or missing tile would include the header `blank-tile: true`.

- https://developers.arcgis.com/rest/services-reference/enterprise/map-tile/

On these two servers, on this date, missing tiles did not use that blank-tile path. Every missing tile in the sample was:

- HTTP 404
- `Content-Type: text/html;charset=utf-8`
- an ArcGIS REST HTML page whose body says `Error: Not Found` and `Code: 404` (572 bytes in the sample)
- no `blank-tile` header
- `x-cache: Error from cloudfront`

Repeating the same URLs with `?blankTile=false` did not change this. Present tiles stayed HTTP 200. Missing tiles stayed HTTP 404. No response in the sample carried `blank-tile`.

Levels the documents list, but the cache does not fill:

| Source | What it lists | What a request did |
| --- | --- | --- |
| Both MapServers, `tileInfo.lods` | Levels 0–23 | Level 17 and level 19 over downtown DC: HTTP 404 |
| Both MapServers, `maxScale` | `9027.977411`, which is level 16 | Level 16 over US land in the sample: HTTP 200 |
| Imagery `minScale` | `295828763.795777`, which is level 1 | Level 0 still returned a JPEG (24,379 bytes) |
| Topo WMTS `default028mm` | Matrices 0–23 | Same 404 at level 17 on the REST tile URL |
| Topo WMTS `GoogleMapsCompatible` | Matrices 0–18 | Same |

USGS prose says tiled base maps "are cached from global scale to a scale of 1:9,000," and that US Topo contours on the Topo basemap are visible "to 1:9,000 zoom scale." Level 16 in this tiling scheme is scale `9027.977411` (about 1:9,028). A 2017 USGS announcement called 1:9,000 "Level 17 in Google Maps tile levels" and 1:18,000 "level 16." That numbering is one higher than the ArcGIS level in `tileInfo` (level 16 ≈ 1:9,028, level 15 ≈ 1:18,056). A request to ArcGIS level 17 is past `maxScale` and returned 404. Catalogue zoom 16 matches the tiles that exist.

- https://www.usgs.gov/faqs/what-difference-between-tiled-and-dynamic-services (page updated 26 June 2025)
- https://www.usgs.gov/faqs/what-are-base-map-services-or-urls-used-national-map
- https://www.usgs.gov/news/technical-announcement/usgs-topo-base-map-updates (11 July 2017)
- https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/WMTS/1.0.0/WMTSCapabilities.xml

**Outside the United States the cutoff is level 9, not level 0.** The Imagery description says Blue Marble: Next Generation and Landsat are used at small to medium scales, and that most of the service is NAIP for the conterminous United States. Topo includes bathymetry and Natural Earth. The sample matches that:

- Levels 0–2 for Washington, London, and a mid-Atlantic point (25°N, 40°W) are the same world tiles. They are HTTP 200.
- London at levels 7 and 8 is still a real overview (Topo about 20–24 KB, Imagery about 17–18 KB; the level-8 Imagery tile is a coast and countryside). Levels 9–16 are HTTP 404 for both services.
- The mid-Atlantic point at levels 7–8 is ocean: Topo is a flat blue JPEG, Imagery is a dark-blue JPEG of 6,280 bytes (level 7) and 4,379 bytes (level 8). Levels 9–16 are HTTP 404.
- Ensenada, Mexico (31.75°N, 116.6°W), just south of the border, is not a clean 404. Imagery level 12 is a pure white JPEG (2,419 bytes, one color). Imagery levels 14 and 16 are HTTP 404. Topo level 16 is HTTP 200 but nearly white (2,502 bytes, six colors, all between 248 and 253).

**Inside the United States, "no photo" can still be HTTP 200.** Over Lake Michigan (43.0°N, 87.2°W) at levels 12, 14, and 16:

- Topo: the same JPEG every time, 2,421 bytes, one color RGB(203, 231, 255). That is styled water, not a missing tile.
- Imagery: the same PNG every time, 872 bytes, 256×256, every pixel RGBA(0, 0, 0, 0). Fully transparent. A viewer that paints transparent pixels black will show a black square. The file is not black imagery.

A level-5 request with row and column 99999 was HTTP 404 on both services, same HTML error as the other misses.

Tiles that exist are `image/jpeg` or `image/png`, `Cache-Control: max-age=86400`, with an `ETag`. `Content-Length` matched the body size. There was no extra `Content-Encoding`. The compressed size of a tile is the JPEG or PNG size.

## Compressed size per tile

USGS does not publish a byte size. The Imagery FAQ says `USGSImageryOnly` uses 256×256 tiles at 96 dpi and 75% compression quality. The live `tileInfo` on both services says 256×256, 96 dpi, format `MIXED`, and `compressionQuality` 85. `MIXED` is why some tiles are PNG (the world Topo tiles, and the transparent water tile) and the rest in this sample are JPEG. The FAQ's 75% and the live 85 disagree; the live value is what the server publishes. The FAQ page was last updated 28 August 2023. The Imagery service text says the data were refreshed in June 2024.

- https://www.usgs.gov/faqs/what-are-urls-imagery-services-national-map-and-are-they-cached-or-dynamic

Sample, one tile per zoom, over downtown Washington, DC (38.9072°N, 77.0369°W). Sizes are the response body in bytes.

| Zoom | Topo | Imagery |
| --- | ---: | ---: |
| 0 | 95,379 PNG | 24,379 JPEG |
| 1 | 96,665 PNG | 26,103 JPEG |
| 2 | 23,884 JPEG | 19,741 JPEG |
| 3 | 22,543 JPEG | 16,585 JPEG |
| 4 | 21,116 JPEG | 15,146 JPEG |
| 5 | 15,694 JPEG | 10,558 JPEG |
| 6 | 33,679 JPEG | 16,230 JPEG |
| 7 | 40,405 JPEG | 16,620 JPEG |
| 8 | 33,377 JPEG | 16,984 JPEG |
| 9 | 46,803 JPEG | 30,516 JPEG |
| 10 | 43,463 JPEG | 25,025 JPEG |
| 11 | 42,508 JPEG | 20,856 JPEG |
| 12 | 36,220 JPEG | 25,793 JPEG |
| 13 | 34,885 JPEG | 27,713 JPEG |
| 14 | 38,946 JPEG | 36,602 JPEG |
| 15 | 25,717 JPEG | 36,051 JPEG |
| 16 | 19,557 JPEG | 34,308 JPEG |

Zooms 0 and 1, and zoom 2 for these three points, are shared world tiles, so London and the Atlantic point are the same sizes there. They diverge starting at zoom 3.

Other places at the zooms that hold almost all of a region's tiles. Bytes. "404" is the HTML error, not an image. Lake Michigan Imagery is the 872-byte transparent PNG.

**Topo**

| Place | z8 | z12 | z14 | z16 |
| --- | ---: | ---: | ---: | ---: |
| Washington, DC (urban) | 33,377 | 36,220 | 38,946 | 19,557 |
| Central Iowa (farmland) | 26,483 | 28,092 | 28,742 | 16,476 |
| Great Smoky Mountains (forest) | 39,251 | 32,929 | 25,415 | 14,378 |
| Anchorage | 29,364 | 33,101 | 20,053 | 17,483 |
| Honolulu | 11,501 | 25,533 | 29,997 | 24,400 |
| Central Nevada (sparse contours) | 20,325 | 19,156 | 11,522 | 5,147 |
| Lake Michigan (open water) | 23,906 | 2,421 | 2,421 | 2,421 |
| London | 20,080 | 404 | 404 | 404 |
| Mid-Atlantic ocean | 2,421 | 404 | 404 | 404 |
| Ensenada, Mexico | 27,050 | 9,437 | 2,620 | 2,502 |

**Imagery**

| Place | z8 | z12 | z14 | z16 |
| --- | ---: | ---: | ---: | ---: |
| Washington, DC (urban) | 16,984 | 25,793 | 36,602 | 34,308 |
| Central Iowa | 14,722 | 24,300 | 32,565 | 27,407 |
| Great Smoky Mountains | 14,572 | 16,683 | 18,644 | 22,403 |
| Anchorage | 23,182 | 21,757 | 14,283 | 24,853 |
| Honolulu | 8,615 | 27,397 | 33,273 | 37,854 |
| Central Nevada | 16,680 | 14,621 | 19,673 | 14,154 |
| Lake Michigan | 11,505 | 872 | 872 | 872 |
| London | 17,505 | 404 | 404 | 404 |
| Mid-Atlantic ocean | 4,379 | 404 | 404 | 404 |
| Ensenada, Mexico | 16,090 | 2,419 | 404 | 404 |

Coordinates: Iowa 41.6°N, 93.6°W; Smokies 35.6°N, 83.5°W; Nevada 39.3°N, 116.4°W; Anchorage 61.2181°N, 149.9003°W; Honolulu 21.3069°N, 157.8583°W; London 51.5074°N, 0.1278°W. The Nevada level-16 Topo tile is only 5,147 bytes and is still a map (contour lines on a light ground, 1,197 distinct colors). Small does not mean empty. The largest Topo tile in the sample is the DC level-9 tile at 46,803 bytes. The largest Imagery tile is Honolulu at level 16, 37,854 bytes. The largest tile of either service is the level-0/1 Topo world PNG, about 96 KB, and a region contains only a handful of those.

A size estimate for a US land region can treat detailed land tiles as roughly 15–40 KB each, with sparse Topo nearer 5–12 KB and open-water Imagery at under 1 KB, and it should not count HTTP 404s as tiles. This sample is ten points, not a census of a region, and it is not a warranty of the next cache refresh.

## Method

164 tile GETs (both services, the places and zooms above, plus DC at levels 17 and 19, plus an out-of-range index) and 10 more with `blankTile=false`. User-Agent `Meshtastic-Android-research/1.0 (small tile sample)`. Pixel checks decoded the PNG or converted the JPEG and counted colors. No `exportTiles` job was downloaded.
