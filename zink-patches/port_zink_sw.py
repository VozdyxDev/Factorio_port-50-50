#!/usr/bin/env python3
"""Port Bruno's (Winlator) Zink software-winsys presentation to a new Mesa tree.
Run from the Mesa source root. Only zink_screen.c / zink_context.c are touched
(winsys + displaytarget are kept in file-scope statics, so no header edits needed).
"""
import re, sys, pathlib

def rd(p): return pathlib.Path(p).read_text()
def wr(p, t): pathlib.Path(p).write_text(t)
def die(msg):
    print("::error::" + msg); sys.exit(1)

Z   = "src/gallium/drivers/zink/"
SCR = Z + "zink_screen.c"
SCH = Z + "zink_screen.h"
CTX = Z + "zink_context.c"
SWH = "src/gallium/include/frontend/sw_winsys.h"
if not pathlib.Path(SWH).exists():
    SWH = "src/gallium/frontends/sw_winsys.h"

def params_of(sig):
    out = []
    for p in sig.split(","):
        ids = re.findall(r"[A-Za-z_]\w*", p)
        if ids: out.append(ids[-1])
    return out

def count_params(proto):
    return len([p for p in proto.split(",") if p.strip()])

sw = rd(SWH)
m = re.search(r"\(\s*\*\s*displaytarget_display\s*\)\s*\(([^;]*?)\)\s*;", sw, re.S)
if not m: die("displaytarget_display prototype not found in " + SWH)
n_disp = count_params(m.group(1))
m = re.search(r"\(\s*\*\s*displaytarget_create\s*\)\s*\(([^;]*?)\)\s*;", sw, re.S)
if not m: die("displaytarget_create prototype not found in " + SWH)
n_create = count_params(m.group(1))
has_destroy = bool(re.search(r"\(\s*\*\s*displaytarget_destroy\s*\)", sw))
print("displaytarget_display params:", n_disp, " displaytarget_create params:", n_create, " destroy:", has_destroy)
if n_create != 8:
    print("::warning::displaytarget_create has %d params (expected 8), check generated call" % n_create)

has_copy_ctx = bool(re.search(r"\bcopy_context\b", rd(SCH)))
print("screen->copy_context exists:", has_copy_ctx)

t = rd(SCR)
if "zink_sw_present_ported" in t:
    print("zink_screen.c already patched"); sys.exit(0)

mf = re.search(r"^zink_flush_frontbuffer\s*\(([^)]*)\)\s*\{", t, re.M)
if not mf: die("zink_flush_frontbuffer not found in zink_screen.c")
P = params_of(mf.group(1))
print("zink_flush_frontbuffer params:", P)
if len(P) < 6: die("unexpected zink_flush_frontbuffer signature: %s" % P)
PSCR, PCTX, PRES, LEVEL, LAYER, HANDLE = P[:6]
extra = P[6:]
if n_disp >= 5:
    nboxes = extra[0] if len(extra) >= 2 else "0"
    box = extra[-1] if extra else "NULL"
    disp_args = "%s, %s, %s" % (HANDLE, nboxes, box)
else:
    box = extra[-1] if extra else "NULL"
    disp_args = "%s, %s" % (HANDLE, box)

copy_ctx_expr = "zink_sw_copy_ctx"
destroy_line = "ws_fb->displaytarget_destroy(ws_fb, e->dt);" if has_destroy else "/* no displaytarget_destroy */"

# ---- file-scope state, inserted before the function's return-type line ----
glob = """#include <stdbool.h>
#include <stdint.h>
#include "frontend/sw_winsys.h"
#include "util/u_inlines.h"
#include "util/u_math.h"
#include "util/format/u_format.h"

/* zink_sw_present_ported: Winlator xlib presentation (Bruno's approach) */
struct zink_sw_dt_entry {
   struct pipe_resource *res;
   struct sw_displaytarget *dt;
   unsigned stride, w, h;
};
static struct sw_winsys *zink_sw_winsys_g;
static struct zink_sw_dt_entry zink_sw_dt_cache[32];
static struct pipe_context *zink_sw_copy_ctx;

"""
prev_line_start = t.rfind("\n", 0, mf.start() - 1) + 1
t = t[:prev_line_start] + glob + t[prev_line_start:]
mf = re.search(r"^zink_flush_frontbuffer\s*\(([^)]*)\)\s*\{", t, re.M)

block = """
   {
      struct sw_winsys *ws_fb = zink_sw_winsys_g;
      if (ws_fb) {
         struct zink_sw_dt_entry *e = NULL, *freeslot = NULL;
         for (unsigned i = 0; i < 32; i++) {
            if (zink_sw_dt_cache[i].res == %(PRES)s) { e = &zink_sw_dt_cache[i]; break; }
            if (!zink_sw_dt_cache[i].res && !freeslot) freeslot = &zink_sw_dt_cache[i];
         }
         if (e && (e->w != %(PRES)s->width0 || e->h != %(PRES)s->height0)) {
            if (e->dt) %(DESTROY)s
            e->dt = NULL; e->res = NULL; freeslot = e; e = NULL;
         }
         if (!e) {
            e = freeslot ? freeslot : &zink_sw_dt_cache[0];
            e->res = %(PRES)s; e->w = %(PRES)s->width0; e->h = %(PRES)s->height0;
            e->dt = ws_fb->displaytarget_create(ws_fb, %(PRES)s->bind, %(PRES)s->format,
                                                %(PRES)s->width0, %(PRES)s->height0,
                                                64, NULL, &e->stride);
         }
         if (!zink_sw_copy_ctx)
            zink_sw_copy_ctx = %(PSCR)s->context_create(%(PSCR)s, NULL, 0);
         if (!zink_sw_copy_ctx)
            zink_sw_copy_ctx = %(PCTX)s;
         if (e->dt) {
            void *dt_map = zink_sw_copy_ctx ? ws_fb->displaytarget_map(ws_fb, e->dt, 0) : NULL;
            if (dt_map) {
               struct pipe_context *cctx = %(CCTX)s;
               struct pipe_transfer *transfer = NULL;
               void *res_map = pipe_texture_map(cctx, %(PRES)s, %(LEVEL)s, %(LAYER)s, PIPE_MAP_READ, 0, 0,
                                                u_minify(%(PRES)s->width0, %(LEVEL)s),
                                                u_minify(%(PRES)s->height0, %(LEVEL)s),
                                                &transfer);
               if (res_map) {
                  util_copy_rect((uint8_t *)dt_map, %(PRES)s->format, e->stride, 0, 0,
                                 transfer->box.width, transfer->box.height,
                                 (const uint8_t *)res_map, transfer->stride, 0, 0);
                  pipe_texture_unmap(cctx, transfer);
               }
               ws_fb->displaytarget_unmap(ws_fb, e->dt);
            }
            ws_fb->displaytarget_display(ws_fb, e->dt, %(DISP)s);
            return;
         }
      }
   }
""" % dict(PSCR=PSCR, PRES=PRES, LEVEL=LEVEL, LAYER=LAYER, CCTX=copy_ctx_expr, DISP=disp_args, DESTROY=destroy_line, PCTX=PCTX)
t = t[:mf.end()] + block + t[mf.end():]

# remember winsys at zink_create_screen entry
mc = re.search(r"^zink_create_screen\s*\(([^)]*)\)\s*\{", t, re.M)
if not mc: die("zink_create_screen not found")
Pc = params_of(mc.group(1))
if not Pc: die("zink_create_screen has no params")
t = t[:mc.end()] + "\n   zink_sw_winsys_g = %s;\n" % Pc[0] + t[mc.end():]

wr(SCR, t); print("zink_screen.c patched (winsys param = %s)" % Pc[0])

# ---------------- zink_context.c: ZINK_CONTEXT_THREADED ----------------
c = rd(CTX)
if "ZINK_CONTEXT_THREADED" in c:
    print("zink_context.c already has ZINK_CONTEXT_THREADED")
else:
    mx = re.search(r"^zink_context_create\s*\(\s*struct\s+pipe_screen\s*\*\s*\w+\s*,\s*void\s*\*\s*\w+\s*,\s*unsigned\s+(\w+)\s*\)\s*\{", c, re.M)
    if not mx:
        print("::warning::zink_context_create not found, ZINK_CONTEXT_THREADED not ported")
    else:
        fl = mx.group(1)
        add = """
   {
      const char *zink_ctx_thr = getenv("ZINK_CONTEXT_THREADED");
      %(f)s &= ~PIPE_CONTEXT_PREFER_THREADED;
      if (zink_ctx_thr && (!strcmp(zink_ctx_thr, "true") || !strcmp(zink_ctx_thr, "1")))
         %(f)s |= PIPE_CONTEXT_PREFER_THREADED;
   }
""" % dict(f=fl)
        c = c[:mx.end()] + add + c[mx.end():]
        c = "#include <stdlib.h>\n#include <string.h>\n" + c
        wr(CTX, c); print("zink_context.c: ZINK_CONTEXT_THREADED ported (default OFF)")
print("DONE")
