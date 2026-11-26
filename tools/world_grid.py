"""Rasterize Natural Earth 110m countries into the dot grid used by Helm's world map.

usage: python3 world_grid.py countries.geojson out.json
"""
import json
import sys

COLS, ROWS = 108, 50
LAT_TOP, LAT_BOTTOM = 83.0, -57.0


def inside(x, y, ring):
    hit = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i][0], ring[i][1]
        xj, yj = ring[j][0], ring[j][1]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


def polygons(geometry):
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"]]
    return geometry["coordinates"]


def cell_of(lon, lat):
    col = int((lon + 180) / 360 * COLS)
    row = int((LAT_TOP - lat) / (LAT_TOP - LAT_BOTTOM) * ROWS)
    return min(max(col, 0), COLS - 1), min(max(row, 0), ROWS - 1)


features = json.load(open(sys.argv[1]))["features"]
countries = []
for f in features:
    p = f["properties"]
    code = p["ISO_A2_EH"] if p["ISO_A2_EH"] not in ("-99", None) else p["ISO_A2"]
    if code in ("-99", None) or p["CONTINENT"] == "Antarctica":
        continue
    countries.append({"code": code, "name": p["NAME"], "continent": p["CONTINENT"],
                      "x": round(p["LABEL_X"], 2), "y": round(p["LABEL_Y"], 2), "polys": polygons(f["geometry"])})

grid = [[-1] * COLS for _ in range(ROWS)]
for r in range(ROWS):
    lat = LAT_TOP - (r + 0.5) * (LAT_TOP - LAT_BOTTOM) / ROWS
    for c in range(COLS):
        lon = -180 + (c + 0.5) * 360 / COLS
        for idx, country in enumerate(countries):
            if any(inside(lon, lat, poly[0]) and not any(inside(lon, lat, hole) for hole in poly[1:]) for poly in country["polys"]):
                grid[r][c] = idx
                break

placed = {v for row in grid for v in row}
for idx, country in enumerate(countries):
    if idx not in placed:
        c, r = cell_of(country["x"], country["y"])
        if grid[r][c] == -1:
            grid[r][c] = idx

out = {
    "cols": COLS, "rows": ROWS, "latTop": LAT_TOP, "latBottom": LAT_BOTTOM,
    "countries": [{k: v for k, v in c.items() if k != "polys"} for c in countries],
    "grid": ["".join("--" if v < 0 else f"{v:02x}" for v in row) for row in grid],
}
json.dump(out, open(sys.argv[2], "w"), separators=(",", ":"))
print(f"{len(countries)} countries, {sum(v >= 0 for row in grid for v in row)} land dots")
