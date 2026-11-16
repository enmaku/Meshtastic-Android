# Map

The basemap under the nodes, and the saved copies of a basemap the app can show with no network.

## Language

**Basemap**:
The map picture under the nodes. One is selected at a time, such as USGS Topo or USGS Imagery.
_Avoid_: layer, dataset

**Offline region**:
A saved copy of one basemap over one geographic area and one zoom range, with an optional name.
_Avoid_: pack, offline map, download

**Zoom range**:
The whole zoom levels an offline region includes, from a minimum through a maximum.
_Avoid_: zoom depth, zoom stack

**Tile hole**:
A place inside an offline region's area and zoom range where the basemap's provider has no tile.
_Avoid_: gap, missing tile, 404

**Coverage**:
How much of an offline region's area and zoom range has real tiles, as opposed to tile holes.
_Avoid_: completeness

**Overzoom**:
Showing an offline region's tiles at a zoom finer than its zoom range, enlarged and blurrier than a real tile.
_Avoid_: upscale, digital zoom

**Underzoom**:
Showing an offline region's tiles at a zoom coarser than its zoom range, reduced to fit a wider view.
_Avoid_: downscale, zoom out fill

**Shared tile**:
A saved tile that more than one offline region of the same basemap covers. It is stored once; the newest save supplies it, and it is deleted only when no region covers it.
_Avoid_: duplicate tile, copied tile
