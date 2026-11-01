#!/usr/bin/env python3
# xlib.c includes inline_sw_helper.h which calls zink_create_screen() without the zink header:
# declare it before that include. Usage: xlib_declare_zink.py <mesa>/src/gallium/targets/libgl-xlib/xlib.c
import sys, re
p = sys.argv[1]
t = open(p).read()
decl = ("#ifdef GALLIUM_ZINK\n"
        "struct sw_winsys;\nstruct pipe_screen_config;\nstruct pipe_screen;\n"
        "struct pipe_screen *zink_create_screen(struct sw_winsys *winsys, const struct pipe_screen_config *config);\n"
        "#endif\n")
m = re.search(r'^#include\s+[<"][^>"]*inline_sw_helper\.h[>"].*$', t, re.M)
if not m:
    sys.exit("inline_sw_helper.h include not found in xlib.c")
t = t[:m.start()] + decl + t[m.start():]
open(p, "w").write(t)
print("declaration inserted into", p)
