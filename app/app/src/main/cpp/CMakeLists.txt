cmake_minimum_required(VERSION 3.22.1)

add_subdirectory(winlator)
add_subdirectory(texcompress)

# ---- Vortek + old Zink + Gladio: independent "legacy" graphics block (own libraries, nothing shared with Turnip path) ----
add_subdirectory(legacy_common)
add_subdirectory(libadrenotools)
add_subdirectory(vortekrenderer)
add_subdirectory(gladiolegacy)
