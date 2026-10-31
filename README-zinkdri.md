# Zink DRI3 (подход Pocket Foundry) как опциональный режим

Ничего не меняется, пока в контейнере нет переменной `ZINKDRI=1`.

## Что внутри
- `.github/workflows/build-zinkdri.yml` - собирает Mesa: libGL (glx=dri) + Zink + Turnip (KGSL) + патч WSI. Результат `zinkdri-<версия>.tzst`.
- `mesa-patches/zinkdri/` - патч "ждать fence перед PresentPixmap" из Pocket Foundry (+ SOURCE).
- `app/.../core/ZinkDri.java` - распаковывает пакет в `rootfs/opt/zinkdri-*` и пишет ICD с абсолютным путём.
- `launcher.diff` - правка `GuestProgramLauncherComponent.java` (31 строка, ветка под `ZINKDRI=1`, только для блока 1: Turnip + Zink).

## Как проверить
1. Положить папки в репозиторий, Actions -> Build Zink DRI3 -> Run workflow (по умолчанию: lfdevs + коммит Pocket Foundry + патч).
   В Summary смотреть: highest GLIBC (должен быть <= glibc рутфса), kgsl > 0, kopper > 0.
2. Скачать `zinkdri-1.tzst` на телефон, например в Download.
3. В контейнере: драйвер Turnip + Zink, переменные окружения:
   `ZINKDRI=1`
   `ZINKDRI_FILE=/storage/emulated/0/Download/zinkdri-1.tzst`
   `GALLIUM_HUD=simple,fps`
   (без `ZINKDRI_FILE` берётся ассет `graphics_driver/zinkdri-1.tzst`, тогда нужна пересборка APK).
4. Если кадр не появляется: добавить `ZINKDRI_SW=1`. Если с ним картинка есть, а без него нет, проблема в DRI3/Present у вашего X-сервера, а не в драйвере.
5. Вернуться: убрать `ZINKDRI`.

## Известные риски
- Не компилировалось и не запускалось (в среде нет Android SDK/javac). Проверить сборку APK.
- Патч рассчитан на коммит `6f90e92`; на другом Mesa может не лечь (workflow остановится с сообщением).
- Ваш `PresentExtension` всё ещё копирует кадр на CPU (`copyArea`), поэтому выигрыш может быть меньше, чем у Pocket Foundry.
- Ваш `DRI3Extension` может не совпасть по версии/модификаторам с тем, что просит Vulkan WSI.
