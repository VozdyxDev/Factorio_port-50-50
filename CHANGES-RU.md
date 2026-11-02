# r53 — Mesa как у Pocket Foundry
Из `runtime/` Pocket Foundry: их Turnip и Zink собраны не из обычной Mesa, а из форка
**lfdevs/mesa-for-android-container** (Mesa 27.0.0-devel, коммит eb3e7fd4…, тег mesa-27.0.0-devel-20261025-r1) с двумя
патчами. У нас — обычная Mesa 26.2.0. Прогон r52: TU_DEBUG=sysmem даёт 40.7 вместо 36.5 FPS (кадр на GPU дешевле) —
то есть упор в то, как драйвер гоняет A830, а это и есть отличие их Mesa.
- `mesa-patches/pocket-foundry/`: их 0001 (Vulkan WSI X11: ожидание кадра в потоке показа, не в потоке игры) и 0002.
- `build-turnip.yml` и `build-zink-dri.yml`: по умолчанию их форк и тег, галочка «Apply the Pocket Foundry patches»,
  можно указать и хэш коммита. Версия файла — из VERSION Mesa (turnip-27.0.0.tzst, zink-27.0.0-dri.tzst).
- Тесты: P01 база, P02 sysmem, P03 OpenGL KGSL, P04 720p.

# r52 — режимы отрисовки Turnip
Прогон r51: ADPF / GameManager ничего не меняют (GPU 389 МГц, 36.5 FPS). По словам пользователя у Pocket Foundry
частота GPU такая же низкая, а FPS выше → при той же частоте их GPU делает кадр быстрее. Сколько работы A830 делает на
кадр, решает режим отрисовки Turnip (плитки GMEM или прямая отрисовка sysmem, binning, LRZ).
- Тесты: P02 TU_DEBUG=sysmem, P03 gmem, P04 nocb, P05 nolrz (везде с нашим noconform), P06 OpenGL KGSL, P07 720p.

# r51 — GPU стоит на минимальной частоте
Прогон r50: `clock_mhz` читается и всё время замера = **389 МГц** (минимум, максимум 1200; governor msm-adreno-tz).
Главный поток игры 43 % ждёт `WAITTIMESTAMP_CTXTID` (окончания работы GPU). GPU занят ~45 % → governor не поднимает
частоту → кадр на GPU в ~3 раза дольше. 4 изображения swapchain — без изменений (35.4).
- `core/PerfHint` (X_PERF_HINT=1): ADPF hint session (PerformanceHintManager) с потоками игры, отчёт длительности каждого
  кадра против цели 16.7 мс; GameManager.setGameState(gameplay). Результат каждого шага — в events perf-summary.
- Тесты: P02 Zink + hint, P03 OpenGL KGSL + hint, P04 OpenGL KGSL, P05 720p …

# r50 — главный поток игры ждёт GPU
Прогон r49 (WaitSampler, 2m_final, нативное): главный поток игры 34 % работает, **43.5 % спит в ioctl на /dev/kgsl-3d0**,
22 % futex; GameUpdate 72 % работает. OpenGL KGSL: 40 % работает, 38.6 % kgsl ioctl (и 39.8 FPS). GPU занят ~46 %.
Процессор и GPU работают по очереди, а не параллельно: игра ждёт окончания кадра на GPU.
- WaitSampler расшифровывает ioctl: для KGSL — номер запроса (WAITTIMESTAMP = ожидание GPU, GPU_COMMAND = отправка).
- Частота GPU: сначала `clock_mhz`, при старте пишутся все источники (что читается).
- Тесты: P02 swapchain ≥ 4 изображений (`vk_x11_override_min_image_count=4`), P03 OpenGL KGSL, P04 FIFO, P05 720p,
  P06 «как Pocket Foundry», P07 xlib.

# r49 — на что ждут потоки игры
Прогон r48: переменные Mesa ни на что не влияют (P01 36.3, без lazy/compact 36.2, «как Pocket Foundry» 36.5,
без glthread 36.7 FPS). Ни один поток не на 100 %, GPU ~46 %: нужно понять, чего ждёт игра.
- `core/WaitSampler`: в фазе замера 100 раз в секунду для каждого потока игры: работает (R) или спит, и если спит —
  в каком системном вызове (futex = ждёт другой поток / fence, ioctl на /dev/kgsl-3d0 = ждёт GPU-драйвер, recvmsg /
  ppoll на сокете = ждёт X-сервер, nanosleep = собственная пауза игры) и что это за дескриптор.
  Итог — раздел «what the game threads do» в perf-summary.txt.
- Список тестов: P01 база, P02 OpenGL KGSL, P03 720p, P04 «как Pocket Foundry», P05 без glthread, P06 xlib.

# r48 — тесты переменных окружения Mesa
Прогон r47 (2m_final, нативное): 34.8 FPS, процесс игры в top-app, все ядра разрешены (гипотеза про ядра не подтвердилась).
Потоки игры: GameUpdate 67 %, главный ~30-45 %, Zink (gdrv0) 13 %, GPU ~44 % — ни один не упёрся: игра ждёт.
Отличие от Pocket Foundry, которое ещё не проверяли: мы задаём переменные, оставшиеся от Winlator
(`ZINK_DESCRIPTORS=lazy`, `ZINK_DEBUG=compact`, `TU_DEBUG=noconform`, `mesa_glthread`, `MESA_VK_WSI_*`, `vblank_mode`,
`MESA_NO_ERROR`), они — ни одной.
- `X_UNSET=A,B,C`: лаунчер удаляет эти переменные последним шагом, кто бы их ни задал.
- Тесты: P02 без lazy/compact, P03 «как Pocket Foundry» (без всех перечисленных), P04 без lazy, P05 без compact, P06 без
  glthread, P07 OpenGL KGSL, P08 720p, P09 xlib. Важные — первыми.
- Частота GPU в perf.csv (`gpu_mhz`) ненадёжна: всегда 389 (gpuclk закрыт SELinux, devfreq/cur_freq не меняется).

# r47 — где Android держит процесс игры
Прогон на мегабазе (r45): нативное 35 FPS, 720p 58 FPS, OpenGL KGSL 38.7; GPU занят 43-57 %, X-сервер ~10 % ядра,
Present 2.7 мс. Частоты: 6 ядер по 2745 МГц (макс. 3532), два самых быстрых ядра (макс. 4473) на 1017 МГц — простаивают;
GPU 389 МГц.
- PerfMonitor пишет в events: допустимые ядра (Cpus_allowed_list), cgroup / cpuset, nice и класс планирования процесса
  игры и приложения; пределы частоты GPU (min/max/cur, governor, thermal_pwrlevel).

# r46 — по итогам первого прогона тестов
Прогон r45 (сейв flexair.zip, Zink DRI): при нативном 2688x1216 игра отдаёт ~60 кадров/с (GPU ~49 %), при 720p тоже 60
(GPU ~26 %). Путь показа кадра держит 60: Present ~5.6 мс на кадр (из них синхронизация кэша dma-buf ~4.2 мс,
копирование ~1.2 мс), поток X-сервера ~35 % одного ядра. Значит, 30 FPS на мегабазе — не путь показа, а сама игра.
- Не находился процесс игры (CPU игры 0 %, «busiest game thread n/a»): в его командной строке есть
  `--executable-path …/factorio-start.sh`, а я такие отбрасывал. Теперь ищется аргумент, оканчивающийся на /bin/arm64/factorio.
- Тесты VSync убраны: ключа vsync в config.ini Factorio 2.x нет («NOT in the config»).
- Новый тест P03: `X_PRESENT_SYNC=0` — X-сервер без синхронизации кэша dma-buf (минус ~4 мс CPU на кадр; смотреть, нет
  ли полос: stripe score).
- Перед тестами выбор сохранения (база, где тормозит); раньше бралось самое новое.
- Короче: 10 с ожидания + 30 с замера; меню — последним тестом.

# r45 — тесты не стартовали (NullPointerException)
- `FileUtils.readString(File)` падал на несуществующем файле (`new String(null)`). Перед первым тестом резервной копии
  конфига ещё нет → `resetConfigFromBackup` → NPE → тест не запускался, прогон «interrupted». Теперь readString
  возвращает null.

# r44 — исправление тестов производительности
- Сейв не загружался: путь к сохранению передавался в `NATIVE_ARGS`, а переменные контейнера — одна строка, разрезаемая
  по пробелам. «--load-game /путь» распадалось на два слова, второе (без «=») роняло разбор переменных
  (`StringIndexOutOfBoundsException` → приложение перезапускалось, прогон «interrupted»). Теперь отдельная переменная
  `NATIVE_LOAD_GAME=<путь>`, лаунчер сам добавляет `--load-game`.
- `EnvVars` пропускает слова без «=» вместо падения.
- В `summary.txt` для каждого теста строка «starting …» (драйвер, конфиг драйвера, экран, сейв, патч конфига); если тест не
  смог стартовать — «ERROR» с полным стеком.

# r43 — тесты производительности и журнал производительности
- Настройки → третья кнопка «Тесты производительности» (`Diagnostics.MODE_PERF`). Каждый тест сам загружает последнее
  сохранение (`--load-game`), ждёт загрузки карты («Loading script.dat») + 15 с, 45 с замеряет и переходит к следующему.
  Используется конфиг игры пользователя; тест меняет только свои ключи (`NATIVE_GFX_PATCH`, только существующие ключи),
  перед каждым тестом конфиг возвращается. В конце возвращаются драйвер, разрешение, переменные и конфиг.
  Тесты: P01 база (Zink DRI, если установлен), P02 VSync выкл, P03 720p, P04 главное меню, P05 Vulkan present
  IMMEDIATE, P06 mesa_glthread=false, P07 720p + VSync выкл, P08 Zink xlib, P09 OpenGL KGSL. Новый тест — одна строка в
  `Diagnostics.buildPerfVariants()`.
- `core/PerfMonitor` + `core/PerfStats`: раз в секунду `perf.csv` — кадры, дошедшие до экрана (все пути), Present /
  MIT-SHM / PutImage / Vortek / Gladio, кадры Android и vsync дисплея, время Present (ожидание блокировок, ожидание
  рендера Android, синхронизация кэша, копирование), кадр и загрузка текстуры Android, запросы X-сервера, CPU игры и
  приложения по потокам (имя, %, ядро), частоты ядер, GPU (kgsl busy/clock), температура, память игры.
  `perf-summary.txt`: средние за фазу замера, самые занятые потоки, самые дорогие X-запросы, подсказки.
  Монитор работает в каждом прогоне диагностики (все три кнопки).
- Галочка «Журнал производительности во время игры»: то же во время обычной игры, папки `perf-*/` рядом с логами
  (хранятся 5 последних).

# r42 — исправление r41: DRI3 1.2 не включался
Лог r41 тот же: `DRI3 not available`. Причина: решение «отвечать DRI3/Present 1.2» проверяло поле `graphicsDriver` активности,
а его обнуляют сразу после запуска окружения — к моменту, когда игра спрашивает версии, оно было null, и сервер отвечал 1.0.
Теперь флаг вычисляется один раз после распаковки драйвера и хранится отдельно. driver-report показывает, что реально
отвечает сервер.

# r41 — X-сервер для Zink DRI (kopper)
Лог r40: `DRI3 not available` → `Couldn't find matching GLX visual`. Kopper (Zink DRI) требует от X-сервера DRI3 ≥ 1.2 и
Present ≥ 1.2, а у нас было 1.0 / 1.0.
- Только когда выбран пакет Zink DRI, X-сервер отвечает DRI3 1.2 / Present 1.2 (остальные драйверы — прежние 1.0, как проверено).
- DRI3: `FenceFromFD` (xshmfence клиента; раньше запрос отвергался, а его fd оставался непрочитанным), `GetSupportedModifiers`
  (пустой список → линейные буферы).
- Present: `NotifyMSC`, `QueryCapabilities`.
- SYNC: заборы из `FenceFromFD` срабатывают в общей памяти (атомарно + FUTEX_WAKE, `SysVSharedMemory.xshmfenceTrigger`), когда
  сервер перестал читать показанный буфер (IdleNotify).
- driver-report: libGL, `dri/` и `deps/` пакета Zink DRI (раньше писал MISSING, потому что смотрел только в usr/lib).

# r40 — Zink DRI (быстрый показ кадров)
- Новый workflow `build-zink-dri.yml`: Zink как обычный DRI-драйвер Mesa (как в Pocket Foundry). Кадр показывается через
  Vulkan-swapchain Turnip (kopper → DRI3 / Present) и не читается каждый раз с GPU в память, как в xlib-сборке.
- Пакет кладётся в свою папку `usr/lib/zink-dri/` (libGL, libgallium, dri/zink_dri.so, deps/), ничего в rootfs не заменяет.
  Ставится в диалоге Zink кнопкой установки `.tzst`, версия `<версия>-dri`. Приложение само видит такой пакет и задаёт
  `LIBGL_DRIVERS_PATH`, `MESA_LOADER_DRIVER_OVERRIDE=zink`, `LIBGL_DRI3_DISABLE=1` (переопределяется переменной контейнера).
- xlib-сборка Zink (`build-zink.yml`, встроенная 26.2.0) остаётся как совместимый вариант; блок Vortek / Gladio не тронут.
- В driver-report строка «Zink build: DRI / xlib».
- R8: правила keep для zstd-jni и JNI-классов (падение release при распаковке rootfs); `commons-compress` 1.21.

# r39 — оптимизация и установка игры из архива
С этой версии нумерация одна: номер = `versionCode` (`appVersion` в `app/app/build.gradle`). Записи ниже («Порт vNN», «vNN»)
велись в разных чатах и пронумерованы по-разному.

## Установка игры из архива
- Новое: `core/GameArchive.java`. Архив `factorio…linux-arm64…_X.Y.Z.tar.xz` (версия из имени, любая) распаковывается прямо в
  контейнер; папка `Download/factorio` по-прежнему копируется, если она есть. Подробно — README, «Где брать игру».
- Пункт главного меню «Установить игру из архива», кнопка «Выбрать архив» в окне «Игра не найдена», предложение обновиться,
  если в Download появился архив новее установленного.
- `GameCopier` разделён: обработка одного файла (`GameCopier.Job`) общая для папки и архива, отчёт тот же.
- Распаковка: защита от путей с `..`, атомарная замена через `factorio.part`, проверка `bin/arm64/factorio` и свободного места.

## Производительность
- Кольцевые буферы Gladio / Vortek (`legacy_common/src/ring_buffer.c`, клиент `gladio/src/ring_buffer.c`): ожидание через
  futex вместо `usleep(100)` в цикле. Сторона, которая умеет будить соседа, ставит флаг `RING_STATUS_*_WAKES`; тогда сосед
  спит до пробуждения (без расхода CPU в простое). Старый клиент без флага (готовый клиент Vortek) — прежний опрос 100 мкс,
  поведение не меняется. Для выигрыша в Gladio пересобрать клиент: workflow *Build Gladio client*, версия 1.4, положить
  `gladio-1.4.tzst` в `assets/graphics_driver/` и поставить `DefaultVersion.GLADIO = "1.4"`.
- Release-сборка без режима отладки, ThinLTO для Vortek / Gladio, `-O3` для сжатия текстур.
- Общий пул потоков `core/AppExecutors` вместо `newSingleThreadExecutor()` без остановки (потоки копились).

## Удалено
- Анимация сборочного автомата на экранах загрузки (класс и картинки).
- `WinHandler` (UDP-сервер для winhandler.exe из Wine) и связанные классы; геймпады работают через `GamepadHandler`
  (мост interposer). Относительное движение мыши (только через Wine) отключено, галочка скрыта.
- Нативная библиотека `gladiorenderer` — вторая, отстающая копия сервера Gladio. Для Turnip/Zink она только создавала
  лишние EGL-контексты; `GLXExtension` теперь хранит простые номера контекстов. Gladio работает через `gladiolegacy`.

## Сборка и CI
- `build.yml`: кэш Gradle и ccache, сборка release + debug, ключ из секретов (без них — прежний ключ).
- Вместо `pickFirsts "**/*.so"` (прятал любые конфликты) — только `libc++_shared.so`.
- `commons-compress` 1.20 → 1.21 (исправления уязвимостей 2021 г.; 1.26 тянет commons-lang3, на котором падает R8 из AGP 7.2), `xz` 1.7 → 1.9.
- Патчи Zink вынесены из YAML в `zink-patches/*.py`.
- `DEBUG_MODE` серверов задаётся из CMake; окончания строк LF (`.gitattributes`).

**Не проверено сборкой и на устройстве** — проверять CI и телефоном.

# Порт v36 — Gladio: RGTC в запросах размера и обратном чтении
В v35 `0x502` исчез, но игра падала на своей проверке `VideoBitmapOpenGL.cpp:267: data.size() == size + offset`. Для RGTC-текстур Gladio отвечал `GL_TEXTURE_COMPRESSED = 0` и `GL_TEXTURE_COMPRESSED_IMAGE_SIZE = w*h*4`, а `glGetCompressedTexImage` не умел RGTC.
- `isCompressedFormatForQuery()` (DXT + RGTC) для `GL_TEXTURE_COMPRESSED` и `..._IMAGE_SIZE`; `isCompressedFormat()` не менялся, потому что от него зависит путь «BGRA8 для хранилища».
- `getCompressedImageSize`: BC4 = 8 байт на блок, BC5 = 16.
- `compressTexImage2D`: BC4/BC5 из RGBA-чтения через `stb_compress_bc4_block`/`bc5`.
- Диагностика: в лог игры идут первые вызовы `glGetCompressedTexImage`, `glGetTexImage`, `glGetTexLevelParameter` (параметры и ответ).
- Не проверено на устройстве.

# Порт v35 — Gladio: BC4/BC5 в glCompressedTexImage2D/SubImage2D
После v34 `glTexImage2D` с RGTC проходит, но дальше падало `glError 0x502 after request code 159` (glCompressedTexSubImage2D): текстура теперь R8/RG8, а обработчик всегда лил BGRA, и декодер считал BC4/BC5 как «bcN=0».
- Добавлен декодер RGTC (беззнаковые 0x8DBB/0x8DBD) в `compressed_texture.c` → плотный R8/RG8.
- `glCompressedTexImage2D`/`glCompressedTexSubImage2D` для RGTC грузят `GL_RED`/`GL_RG`, а не BGRA.
- Знаковые RGTC (0x8DBC/0x8DBE) не декодируются. Не проверено на устройстве.
- Диагностика: строка «Port build» в driver-report теперь v35, versionCode 35.

# Порт v34 — Gladio: glTexImage2D с RGTC (BC4/BC5) → INVALID_VALUE
Лог Adreno 830: атласы создаются с internalformat `0x8dbd` (RG_RGTC2) и данными RG/UBYTE, `convertTexImageFormat` сбрасывал его в `GL_NONE` (в таблице каналов нет RGTC, а в fallback нет `GL_RG`), `glTexImage2D` давал `0x501`, затем `0x502` и abort в `VideoBitmapOpenGL.cpp:267`.
- RGTC1/2 (и знаковые) для обычного хранилища отображаются в `R8`/`RG8`/`R8_SNORM`/`RG8_SNORM`.
- В fallback добавлен `case GL_RG` → `GL_RG8`.
- Файл: `app/app/src/main/cpp/gladiolegacy/include/texture_utils.h`.
- Не проверено на устройстве. Следующий подозреваемый: `0x502` на request 159 (DXT5/RGTC через PBO).

# v38 — Gladio: «Failed to load glad»
Лог с телефона: окно создаётся, GLX-запросы проходят, дальше `Error Util.cpp:81: Failed to load glad`.
Причина: `glXGetProcAddressARB` в клиенте Gladio знал только имена `glX*` и для остальных возвращал NULL (Wine берёт GL-функции прямо из библиотеки через dlsym, SDL и glad игры запрашивают через glXGetProcAddress все, включая `glGetString`). Теперь для имён `gl*` функция ищется в самой библиотеке (`dladdr` + `dlopen(RTLD_NOLOAD)` + `dlsym`).
**Версия клиента 1.3**: запустить workflow *Build Gladio client* (version = 1.3, по умолчанию уже так), положить `gladio-1.3.tzst` в `assets/graphics_driver/` вместо файла-заглушки. Новая версия сбрасывает кэш драйвера, перекладывать ничего вручную не нужно.

# v37 — Gladio: исходники клиента в репозитории + сборка через CI
- Исходники клиента Gladio лежат в `gladio/` (из `gladio-116c0d1`), сборка — `.github/workflows/build-gladio.yml` (ARM-раннер, как gamepad-libs). Артефакт `gladio-<версия>.tzst` кладётся в `assets/graphics_driver/`, версия — в `DefaultVersion.GLADIO` (сейчас 1.2).
- Клиент теперь линкуется с libX11 (в старой сборке `XGetVisualInfo` оставался неразрешённым, поэтому понадобился preload).
- Реализовано: `glXQueryExtension` (возвращает True; SDL иначе писал «GLX is not supported»), `glClearBufferfv/iv/uiv` (клиент + серверный обработчик).
- Пока лежит запасной ассет `gladio-1.2.tzst` = старый 1.1 с бинарным патчем `glXQueryExtension` (без `glClearBuffer*`). После сборки в CI заменить его на собранный.
- Диагностика выносит строки `gladio:` / `unimplemented` в `result.txt`.
- Что ещё в Gladio не реализовано и может понадобиться Factorio: `glCopyImageSubData`, `glTexStorage2D/3D`, `glInvalidateFramebuffer`, `glVertexAttribIPointer`, `glGetIntegeri_v`, `glGetInteger64v`, `glGetUniform*v`, функции KHR_debug. Точный список даст анализ бинарника игры или лог первого запуска.

# v36 — разбор прогона на Mali-G610 (Xiaomi, MT6886)

## Что показали логи
- «Падения» `status 139` (L6, L7) — **ошибка самой диагностики**: PNG скриншота сжимался в главном потоке 5+ с, MIUI App Scout считал приложение зависшим и убивал его (в logcat `APP_SCOUT_HANG ... Bitmap.compress`). Теперь копирование, анализ и сжатие идут в фоновом потоке, снимки в JPEG.
- Синхронизация кэша CPU (`memorySync` 1/2) полосы **не убрала** (stripe score L1 1.71, L3 1.62, L4 1.37, L5 1.34; L2 «0.00, frozen» — картинка стояла). Гипотеза про кэш не подтвердилась. На скриншотах целы крупные объекты (трубы, ленты, рельсы), смазаны земля и мелкие спрайты.
- Диалог игры «Configuration file has invalid contents» в диагностике — следствие самой диагностики: `config-native.ini` хранится между запусками, вариант с `max-texture-size=4096` записал в него недопустимый ключ, и он остался для всех следующих вариантов. Исправлено: каждый вариант начинается с `NATIVE_GFX=reset`, пользовательский конфиг сохраняется перед прогоном и возвращается после; вариант с `max-texture-size` удалён.
- Gladio: после preload libX11 следующая ошибка — `gladio: unimplemented call glXQueryExtension` → SDL «GLX is not supported». В клиентской libGL Gladio функция была заглушкой `return 0`. В ассете `gladio-1.1.1.tzst` она пропатчена на `mov w0,#1; ret` (версия 1.1 → 1.1.1, чтобы файлы переустановились).

## Новые варианты долгой диагностики (чистый конфиг игры для каждого)
L1 контроль со слоем BCn · L2 без слоя + без сжатия текстур в игре (BC-текстур нет вообще: отделяет слой BCn от базового пути) · L3 слой + без сжатия · L4 `ZINK_CONTEXT_THREADED=0` · L5 без слоя + без сжатия + unthreaded · L6 DMA Buffer + sync 2 + unthreaded · L7 Gladio.
`ZINK_CONTEXT_THREADED` теперь можно переопределить переменной контейнера (раньше принудительно ставилась в 1).

# v35 — Mali / MediaTek: полосы на картинке, пресеты, Gladio, расширенная диагностика

## Что показала диагностика (Mali-G610 MC4, MT6886, Vortek + Zink 22.2.5)
- CPU-декод BCn (`bcnMode=0`): PSNR теста 5.67 / 7.90, игра зависает после «Parallel sprite loader initialized» — режим непригоден на Mali.
- GPU-слой BCn (`bcnMode=1`) работает, меню за ~48 с, но по всей картинке горизонтальные полосы. Полосы остаются и при `texture-compression-level=none`, значит дело не в декодере BCn, а в пути загрузки данных в GPU.
- Resource Memory: Native Hardware Buffer — `vkMapMemory failed` + SIGSEGV; Opaque FD — нет GL-контекста; DMA Buffer работает так же, как по умолчанию.
- Gladio: `libGL.so.1: undefined symbol: XGetVisualInfo` — окно не создаётся.

## Что сделано
1. **Синхронизация кэша CPU для общей памяти** (`vortekrenderer/src/resource_memory.c`, настройка «Синхронизация кэша CPU» в диалоге Vortek, ключ `memorySync`).
   Гипотеза: память хоста выдаётся из AHardwareBuffer / dma-buf и мапится в процессе игры; flush/invalidate уходили в драйвер, где эта память не смаплена, поэтому кэш CPU не сбрасывался и GPU читал устаревшие строки (отсюда горизонтальные полосы).
   - `0` — как раньше;
   - `1` — `DMA_BUF_IOCTL_SYNC` при `vkFlushMappedMemoryRanges` / `vkInvalidateMappedMemoryRanges`;
   - `2` — плюс сброс кэша всех host-visible блоков перед каждым `vkQueueSubmit` (медленнее, но самый надёжный).
   Если ioctl не поддерживается (opaque fd), он один раз отключается для этого блока. **Не проверялось на устройстве** — проверять диагностикой (см. ниже).
2. **Пресеты Mali** (`core/MaliPresets.java`, выпадающий список вверху диалога Vortek). По умолчанию «Не выбрано» = свои настройки; пресет заполняет поля диалога, а его игровые параметры (`NATIVE_GFX`) применяются при запуске, если в контейнере свой `NATIVE_GFX` не задан. Пресеты: сбалансированный, надёжный (без сжатия текстур, sync 2, dma-buf), BCn через ASTC, мало памяти. Режимы, которые на Mali падают или зависают (CPU-декод, AHB, Opaque FD), в пресеты не входят. Новый пресет — одна строка в `MaliPresets.all()`.
3. **Gladio**: libGL из пакета не слинкован с libX11, а SDL открывает libX11 приватно. При выборе Gladio libX11.so.6 теперь подгружается через `--preload` (`GuestProgramLauncherComponent`).
4. **Диагностика**: автоматическая оценка картинки по скриншотам — «stripe score» (≈1 нормальная картинка, заметно больше 1.5 — полосы) и пометка «такой же, как предыдущий» (игра зависла). Результат в `result.txt` и `summary.txt`. Новые варианты: sync 0/1/2, dma-buf + sync, пресет «мало памяти» (раньше L6 не дошёл — прогон оборвался), Gladio после фикса. Рискованные варианты (CPU-декод, Opaque FD, AHB) в коротком прогоне вынесены в конец, из долгого убраны. В `vortek-report.txt` добавлены `memorySync` и наличие `/dev/dma_heap/*`.
5. Как читать результаты: сравните `stripe score` у L1 (sync 0, контроль), L2 (sync 1) и L3 (sync 2). Если L2/L3 заметно ниже L1 — причина подтверждена; если нет — полосы не из кэша, нужно смотреть путь копирования буфер → изображение в слое BCn.

Собрано без проверки компиляцией (нет NDK/Gradle в окружении) — проверять сборкой в CI.

# Диагностика (Настройки → «Диагностика» / «Диагностика (долгая)»)
Автоматический прогон игры с набором вариантов графики, по очереди, без участия человека (`core/Diagnostics.java`):
- **Диагностика**: ~17 вариантов по 15 с (Vortek+Zink/Gladio, режимы BCn, тип памяти ресурсов, версия Vulkan, лимит памяти, настройки сжатия текстур игры; на Adreno ещё Turnip). Показывает, как стартует драйвер.
- **Диагностика (долгая)**: ~7–9 вариантов по 5 мин, скриншот каждую минуту и в конце (игра успевает дойти до меню).
- Для каждого варианта в папке `Documents/Factorio/diagnostics-<время>/<id>/`: `log.txt` (вывод игры), `logcat.txt` (logcat приложения, там сообщения Vortek), `driver-report.txt`, `vortek-report.txt`, `result.txt` (статус + ключевые строки), `variant.txt`, скриншоты `shot-*.png` и `final.png`. В корне `summary.txt` и `diagnostics-<время>.zip` для отправки.
- Настройки пользователя сохраняются перед прогоном и восстанавливаются в конце (или при следующем запуске приложения, если прогон прервали).
- Если игра завершилась сама, вариант записывается как `game exited early` и прогон идёт дальше; если окружение не стартовало, срабатывает таймаут.
- Во время диагностики завершение игры больше не закрывает приложение (раньше обработчик завершения убивал процесс, и цепочка обрывалась после первого варианта).
- Новый вариант добавляется одной строкой в `Diagnostics.buildVariants()`.
- Исправлено: список «Exposed Extensions» сохранялся через `:`, а читался по `|` (баг оригинального Winlator), теперь читаются оба разделителя.

# Блок «Vortek + Gladio / Zink 22.2.5» (оригинальный Winlator) — отдельно от Turnip

В настройках графики теперь два независимых блока, активен ровно один (галочка):
1. **Turnip + OpenGL / Zink** — как раньше, код не менялся.
2. **Vortek + Gladio / Zink 22.2.5** — стек оригинального Winlator.

Как достигнута независимость:
- Нативно: свои библиотеки `vortekrenderer`, `gladiolegacy`, `libadrenotools`. Нужные им функции Бруно (arrays, ring_buffer, ashmem) вынесены в приватную статическую `legacy_common` со скрытыми символами — общий `libwinlator.so` они не подменяют. Единственная связь с `libwinlator` — `globalEGLContext` для Gladio.
- GLX: для блока 2 используется `LegacyGLXExtension` (копия GLX Бруно: отвечает на CreateContext и т.д.), для блока 1 — прежний `GLXExtension`. Выбор в `XServer.setupExtensions()` по `isLegacyGraphics()`.
- Запуск: `extractLegacyGraphicsDriverFiles()` — отдельный метод со своим cacheId (`legacy-…`), свои файлы (`vortek-2.1`, `gladio-1.1`, `zink-22.2.5`), симлинки `libGL.so.1`/`libGL.so`. Старый путь `extractGraphicsDriverFiles()` для Turnip/Zink не изменён (кроме удаления `libvulkan_vortek.so` при возврате).
- Хранение: активный блок = `graphicsDriver` (`vortek,gladio`); выбор и настройки блоков лежат в extras (`graphicsBlockTurnip`, `graphicsBlockVortek`, `graphicsConfigVortek`), формат блока 1 прежний.
- Выбор драйвера adrenotools в настройках Vortek не переносился (системный драйвер).
- Собрано без проверки компиляцией (нет NDK) — проверять сборкой в CI.

## Отчёт Vortek (vortek-report.txt)
При каждом запуске блока Vortek рядом с `factorio-native.log` пишется `vortek-report.txt`: настройки Vortek, состояние BCn-слоя (выкл / загружен / ошибка) и всё, что сообщает системный Vulkan-драйвер (версия, лимиты, выравнивания, память, поддержка форматов, расширения). В `driver-report.txt` добавлен раздел для блока Vortek.

## Диагностика «игра не найдена»
Если бинарника игры нет в rootfs, запуск теперь останавливается с понятным текстом (тост + `factorio-native.log`): NATIVE_SRC не задан / папка не видна приложению / нет прав на чтение / нет `bin/arm64/factorio` / копия не завершилась. Раньше загрузчик glibc выдавал только «cannot open shared object file».

## Дополнение для Mali / MediaTek (по мотивам Winlator Mali)
1. **Исправления Gladio из Winlator Mali** перенесены в `gladiolegacy` (только блок Vortek/Gladio): пересоздание display buffer при смене размера окна, id фреймбуферов через `glGenFramebuffers`, убран лишний `glFlush`, явные `precision` в шейдерах GLSL ES 3.20, include в `stb_dxt.h`.
2. **GPU-эмуляция BCn** (опция «BCn Emulation» в настройках Vortek, по умолчанию выключена = прежнее CPU-декодирование):
   - слой `libbcn_layer.so` (leegao, модификация WinMali-Dev; ассеты `leegao_bcn.tzst` и `leegao_bcn_legacy.tzst`) собран под bionic и в glibc-процессе игры не загрузится, поэтому он подключается внутри сервера Vortek, который работает в Android-процессе;
   - `bcn_layer.c` вручную встраивает слой между Vortek и системным драйвером (цепочка instance/device по интерфейсу loader–layer); слой сообщает textureCompressionBC=true, и CPU-декодер Vortek сам отключается;
   - при любой ошибке загрузки или `vkCreateInstance` слоя Vortek продолжает работать как раньше; на Adreno слой не используется;
   - режимы: CPU / GPU (новая сборка) / GPU legacy → ASTC / GPU legacy → ETC2.
   - **Не проверялось на устройстве.** Лицензия слоя — на стороне его авторов (Winlator Mali / leegao).

# Что изменено в этой версии

1. **Workflow** `.github/workflows/build.yml`: имя `Build Factorio`, артефакт `Factorio-debug`.
2. **Боковая шторка в игре**: «Настройки» стоят над «Выход», «Выход» — отдельная кнопка, прижатая к самому низу шторки
   (`res/layout/xserver_display_activity.xml`, `res/menu/xserver_menu.xml`).
3. **Геймпад ↔ экранное управление** (`XServerDisplayActivity`): геймпад подключили — экран управления скрывается,
   отключили — появляется. В диалоге «Управление» есть галочка «Показывать экран управления при подключённом геймпаде»
   (сохраняется), с ней экранное управление остаётся и при геймпаде.
4. **Драйверы** — единый реестр `core/DriverPackages.java`:
   - один пункт «Установить драйвер из файла» (Настройки → Графический драйвер), тип определяется по содержимому файла:
     `.zip` с `meta.json` → bionic-драйвер (Mali PanVK, Adreno, Xclipse...), грузится через Vortek+adrenotools;
     `.tzst` → сборка Turnip для proot-rootfs (glibc). Любой драйвер можно выбрать на любом устройстве.
   - список Vulkan: Turnip / Vortek (системный) / Auto / все установленные драйверы. Выбор драйвера сам выставляет
     рендерер и конфиг. Формат хранения в контейнере не менялся.
   - «Auto» = лучший установленный драйвер под GPU устройства (Adreno/Mali/Xclipse/PowerVR определяются по GL_RENDERER).
     Семейство драйвера берётся из `meta.json` (`gpuFamily`/`gpuFamilies`) или определяется по backend/имени.
     Несовпадение с GPU — только предупреждение, не запрет.
   - Исправлено: импорт читал первый попавшийся `*.json` (у zenithblue это MANIFEST.json без libraryName) и брал путь
     только для основной памяти (Загрузки/Telegram молча не работали). Теперь берётся `meta.json`, файл читается по Uri.
   - Turnip тоже заменяемый: кнопка установки в диалоге Turnip → «Из файла» (имя версии берётся из имени файла).
   - proot/glibc-структура не тронута.

5. **VirGL и Gladio убраны** как выбираемые драйверы (код, диалог, ассеты, нативный virglrenderer). Нативная библиотека
   `gladiorenderer` оставлена: через неё работает серверная часть GLX (`GLXExtension`).
6. **Zink сменный, как Turnip.** Встроенный Zink 26.2.0 и Turnip 26.2.0 остаются «рабочими по умолчанию».
   Собранные вами пакеты ставятся кнопкой «Установить драйвер из файла»: тип определяется по содержимому `.tzst`
   (`libvulkan_freedreno.so` -> Turnip, `libGL.so*` -> Zink). Версию Zink выбирает кнопка настроек рядом с OpenGL.
7. Добавлен workflow `build-turnip.yml` (Turnip для вашего rootfs, с маркером пути и недостающими библиотеками xcb).
8. **driver-report.txt** (Documents/Factorio): при каждом старте пишет, какие файлы драйверов реально лежат в rootfs
   (размер, строка `Mesa x.y.z (git-...)` из самой библиотеки), чтобы отличать новый драйвер от старого.
9. **Экспериментальный Turnip** - `.github/workflows/build-turnip-experimental.yml` + папка `turnip-patches/`
   (рецепт и патчи из проекта Banners-Turnip, GPL-3.0): Mesa main + патчи Adreno 8xx. Ставится рядом отдельной версией
   вида `26.3.0-a8xx-<коммит>`, рабочие 26.2.0/26.2.4 не затрагиваются.


## v22 — исправление зависания нативного GLX (glXCreateContext)
- Причина: запрос GLXCreateContext (24 байта) разбирался как 21 байт; 3 байта выравнивания оставались в потоке
  и следующий запрос читался со сдвига, поэтому сервер переставал отвечать клиенту, а клиент вечно ждал в poll()
  на сокете X (подтверждено watchdog-ом glxtest: главный поток в ppoll на X-сокете).
  Zink-путь это не затрагивало, потому что он использует glXCreateContextAttribsARB, который читается целиком.
- XClientRequestHandler: после обработчика расширения пропускаются непрочитанные байты запроса
  (в debug-лог пишется "ext request left N unread byte(s)").
- XClientRequestHandler: запрос к неизвестному расширению теперь возвращает ошибку, а не молча игнорируется.
- GLXExtension.createContext: убран ответ — по протоколу GLX у CreateContext ответа нет.

## v23 — GLX: ответ на IsDirect и безопасные заглушки
- После исправления зависания (v22) glXCreateContext стал возвращаться, но Mesa сразу шлёт GLXIsDirect (opcode 6)
  и считает контекст неудачным, если получает ошибку (лог: "GLXTEST: FAIL glXCreateContext").
  Теперь сервер отвечает isDirect = 1.
- GLXMakeCurrent (5) и GLXMakeContextCurrent (26) отвечают ненулевым contextTag.
- GLXClientInfo (20), SetClientInfoARB (33), SetClientInfo2ARB (35) принимаются и игнорируются
  (раньше в ответ приходил X Error BadImplementation).

## v24 — DRI3: диагностика буфера кадра, GLX GetDrawableAttributes
- Нативный Freedreno/KGSL уже создаёт контекст и отдаёт GL_RENDERER = Adreno (TM) 830, OpenGL 4.6 (glxtest после v23).
- Остался сбой DRI3: PixmapFromBuffer завершается BadAlloc (X error 11), потому что mmap() fd от клиента не удался.
  DRI3Extension.pixmapFromFd теперь пишет в debug-лог, что за fd пришёл (ссылка /proc/self/fd, fdinfo, размеры),
  и при неудаче mmap только для чтения пробует чтение/запись.
- GLXGetDrawableAttributes (29): ответ с пустым списком атрибутов (раньше X Error BadImplementation).

## v25 — DRI3: размер буфера кадра
- Диагностика v24 показала: клиент присылает dma-buf (exp_name: system, 1 МиБ) и в запросе PixmapFromBuffer указывает size=0,
  поэтому mmap(длина 0) всегда падал (BadAlloc). Теперь размер берётся как stride*height, если клиент прислал меньше.

## v26 — GLX: у CreateContextAttribsARB нет ответа
- Factorio (SDL) падал на создании контекста: "xcb_request_check: Assertion `!reply' failed". Причина: запрос
  GLXCreateContextAttribsARB (opcode 34) обрабатывался с ответом из 32 байт, но у него ответа нет по протоколу;
  xcb_request_check() в клиенте на лишний ответ делает abort().
- Ответ убран. Заодно снято ограничение версии GL <= 3.3 (контекст рисует сам клиент; на сервере только заглушка).
  Запрошенная версия пишется в debug-лог ("GLX createContextAttribsARB: requested GL x.y").

## v27 — метка сборки
- Первый GLX-запрос пишет в debug-лог строку "BUILD v27: ext-unread-skip, glx-createctx-noreply, glx-attribs-noreply,
  glx-isdirect, dri3-size-fix". Если этой строки нет в logs.txt, в APK нет всех исправлений.

## v28 — нативный OpenGL: синхронизация буфера кадра (кубики и мерцание)
- Игра в нативном GL запустилась (заставка с прогрессом), но картинка "сыпалась кубиками" и мигала.
  Вероятные причины: (1) CPU читает dma-buf, который писал GPU, с устаревшим кэшем процессора;
  (2) кадр копируется, пока GPU ещё дорисовывает бины (тайлы Adreno).
- Native: SysVSharedMemory.dmabufSync(fd, start) -> ioctl DMA_BUF_IOCTL_SYNC (READ, START/END).
- DRI3Extension: fd dma-buf не закрывается, а хранится в Drawable.syncFd и закрывается при уничтожении пикскарты.
- PresentExtension: чтение буфера обёрнуто в DMA_BUF_IOCTL_SYNC; в лог пишется "PRESENT #N ... sampled non-zero pixels";
  полностью нулевой dma-buf кадр (не больше 3 подряд) пропускается, чтобы не мигало чёрным (только нативный GL).
- Метка сборки: BUILD v28.
- build-opengl.yml: новый параметр swap_finish (по умолчанию включён) — патч Mesa: glFinish() перед glXSwapBuffers.

## v29 — нативный GL: диагностика ввода и запасной фокус
- В нативном режиме игра рисуется, но клики и клавиши не доходят (в Zink работало). В коде сервера фокус клавиатуры ставится
  только окну, у которого isApplicationWindow() (есть WM_NAME и WM_HINTS.window_group == id окна).
- DesktopHelper: запасной фокус — если фокуса нет или он на корневом окне, верхнему окну с WM_CLASS фокус отдаётся и без этих условий.
  В лог пишутся строки "FOCUS: ..." (какие окна мапятся, их name/class, кому отдан фокус).
- InputDeviceManager: в лог пишутся строки "INPUT: ..." — почему нажатие/клик отброшены (нет фокуса, нет слушателя, окно выключено)
  или кому первые 8 событий доставлены.
- Метка сборки: BUILD v29.

## r32 — независимый зонд ввода и трассировка X11
- glxtest v7: отдельные процессы/соединения для nogl, Xlib, Xlib+XSync и XCB;
  корректный владелец очереди, реальное время, пути загруженных библиотек.
- X11_WIRE_TRACE=1 включает ограниченный вывод заголовков и фактического числа
  записанных в сокет байтов. Отключено по умолчанию; исправление ввода не заявлено.
- Протокол GLX/DRI3/Present, фокус, графические настройки и частоты не менялись.
- Инструкция и ограничения: docs/INPUT-DIAGNOSTICS-RU.md.

### Зонд v7.1
- Исправлен приём аргументов Factorio, автоматически добавляемых лаунчером.
- Проверены компиляция и прохождение разбора аргументов до XOpenDisplay.

## r33 — исправление диапазона событий GLX
- QueryExtension(GLX) теперь возвращает first_event=72 вместо унаследованного 0.
  Mesa устанавливает 17 конвертеров Xlib: ранее диапазон 0..16 перекрывал
  клавиатуру, мышь и фокус, и GLX-конвертер отбрасывал эти события.
- Выделен диапазон 72..88: core-события ниже 64, MIT-SHM=64, RANDR начинается с 89.
- Основание: glxtest v7.1 на устройстве: без GL мышь приходит, Xlib и Xlib+XSync
  после GL дают ноль ввода, XCB получает 31/31 клавиш и мышь; записи в сокет
  во время теста полные. Восстановление ввода r33 на устройстве ещё не проверено.
- Графические библиотеки, частоты, ожидания GPU и обработчики фокуса не менялись.

## r34 — встроенный OpenGL и переключение
- Встроен предоставленный mesa-kgsl-26.2.0-devel-20260709-r7.zip.
- OpenGL/Freedreno KGSL и Zink доступны в выборе OpenGL-драйвера.
- Выбор Zink больше не перекрывается NATIVE_MESA_SRC; библиотеки нативного OpenGL хранятся отдельно от rootfs/usr/lib.
- Версионная распаковка с проверкой обязательных файлов и восстановлением неполной установки.
- Сохранено исправление событий GLX из r33. Проверка на устройстве для r34 ожидается.

## r34-clean (чистка проекта, без изменения поведения)
- Удалён мусор: `.github/workflows/1.txt` (пустой), `core/ZipUtils.java` (нигде не использовался).
- Удалены неиспользуемые ресурсы (~130 КБ): `ic_launcher_round` во всех плотностях (в манифесте нет roundIcon).
- Удалены папки `docs/`, `gladio/`, `vortek/`, `installable_components/`, `local-build/`, `tests/`, `tools/` и workflow `build-glxtest.yml` (в сборке APK не участвуют; `libgladiorenderer`/`libvortekrenderer` собираются из `app/.../cpp`). `ПРОЧИТАЙ-R34.txt` удалён.
- `.gitignore` дополнен (.idea, *.iml, *.apk, .DS_Store); права файлов нормализованы (644/755); `proguard-rules.pro` очищен от шаблона.
- `SOURCE-SHA256.json` пересчитан. versionCode/versionName не менялись.
- OpenGL: `opengl-kgsl-r7.zip` заменён на `opengl-kgsl-r7.tzst` (5 МБ вместо 8.9 МБ, в архиве только `lib/*` и `dri/kgsl_dri.so`). `BundledOpenGL` распаковывает его через `TarCompressorUtils`; workflow `build-opengl.yml` теперь тоже выпускает `.tzst`.
- Первый запуск: выбор 720p / 1080p / нативное над индикатором копирования (`ScreenResolution`, `DownloadProgressDialog`, `RootFSInstaller`). Если копирование закончилось раньше выбора — окно ждёт нажатия. Ширина считается по реальному соотношению сторон экрана.
- Драйверы: добавлены встроенные Turnip 26.2.4 и 26.3.0-beta (`graphics_driver/turnip-*.tzst`), по умолчанию остаётся 26.2.0. При смене драйвера папка `turnip-deps` предыдущей сборки удаляется.
- Удалены Vortek (Java-компонент, диалог, `vortek-2.1.tzst`, нативные `vortekrenderer` и `libadrenotools`), пункты «Авто» и пакеты Adrenotools/Mali (`DriverPackages` оставил только установку Turnip/Zink из `.tzst`). `bc_decoder.h` перенесён в `gladiorenderer/include`. Старое значение `vortek` в настройках контейнера автоматически становится `turnip`.
- Кнопка «Установить драйвер из файла» убрана с экрана настроек; значок установки в окне Turnip (и Zink) теперь сразу открывает выбор файла (без выбора «скачать из репозитория / из файла»). Загрузка компонентов из репозитория удалена.
- Лишние строки (Vortek, Auto, установка из репозитория) удалены из всех языков.
- Экран первой установки: подпись «Установка системных файлов… %» вынесена наверх, выбор разрешения — вниз; кнопки оранжевые (`ButtonAccent`, `drawable/button_accent.xml`) и двухстрочные: «720p» + реальный размер (раньше вторая строка обрезалась из-за `lines=1` в базовом стиле).
- Настройки контейнера: над «Размер экрана» добавлены оранжевые кнопки 720p / 1080p / Нативное; нажатие выбирает «Пользовательский» и подставляет ширину и высоту по соотношению сторон экрана.
- Все кнопки приложения теперь оранжевые (`ButtonNeutral`/`ButtonPositive` используют `button_accent`); старые PNG-кнопки (`button_*_normal/pressed.9.png`) удалены.
- Сжатие текстур при копировании игры: второй шаг на экране первой установки (Оригинал / Низкое ×5 / Высокое ×10), `GameCompression`, `GameCopier` (копирование в несколько потоков с прогрессом), `TextureCompressor` + нативная `libtexcompress` (`cpp/texcompress`). Сжиматель протестирован на ПК (gcc, ASan/UBSan, проверка libpng). Звук не сжимается.
- Экран первой установки: анимация снова строго по центру; кнопки сжатия идут в порядке «Высокое, Низкое, Оригинал».
- Из настроек удалена кнопка «Переустановить системные файлы» (вместе со строками).
- Сжатие: коэффициенты изменены — «Высокое» теперь ×5, «Низкое» ×2. Сжиматель переписан на более точный: уточнение палитры k-means (прежний алгоритм давал сдвиг оттенков), для «Низкое» — дизеринг; палитра подбирается как можно больше при заданном коэффициенте.
- Отчёт `compression-report.txt` (сколько PNG сжато / пропущено и почему, полный список пропущенных) и сообщение с итогом после копирования.
- Новый пункт меню «Файлы игры» (`FileManagerFragment`): корни «Игра» (`home/xuser`) и «Download», выйти за их пределы нельзя; просмотр, правка текстовых файлов, копирование, перенос, переименование, удаление, новая папка, добавление файлов.
- Сжатие больше не трогает меню и GUI: PNG из `data/core/graphics/` (GUI-атласы, курсоры, `color_luts`), любые файлы с «gui», «lut», «menu», «background», «logo» в названии, а также `graphics/icons` и `graphics/technology` копируются без изменений (`GameCompression.isProtected`). В отчёте они считаются отдельно («protected»).
- Копия `compression-report.txt` кладётся ещё и в `home/xuser` (корень «Игра» в файловом менеджере).
- Файловый менеджер: пункт «Свойства» (имя, тип, путь, размер в байтах, для папки — число файлов и папок, дата, права доступа); жест «назад» поднимает на папку выше и выходит из менеджера только из корня «Игра»/«Download»; редактор текстовых файлов — на весь экран и с многострочным текстом (общий стиль `EditText` был однострочным), с подтверждением при выходе с несохранёнными изменениями.

## Gladio клиент: пакетная отправка GL-вызовов (v7)
- `gl_send` копит мелкие запросы в буфере (до 256 КБ) и отправляет одной записью в кольцо вместо двух на каждый вызов. Формат потока тот же — сервер (`gladiolegacy`) не менялся.
- Отправка пачки: каждые N вызовов (по умолчанию 32, env `GLADIO_BATCH`, `0` = выключить), при переполнении, перед большим payload (текстуры, `glEnd`), перед любым ожиданием ответа (`gl_recv`), на `glFlush`/`glFinish`/`SwapBuffers`, при уничтожении контекста.
- Файлы: `gladio/include/gladio.h`, `ring_buffer.h/.c`, `gl_context.h`, `src/main.c`, `src/gl_calls.c`. Нужна пересборка клиента в CI (версию `1.3` не меняли).

## Gladio клиент: падение в `RingBuffer_write` внутри Vortek (v7)
Лог: после `Initialised OpenGL: Gladio` SIGSEGV в `RingBuffer_write` ← `vt_call_vkEnumerateInstanceExtensionProperties`. `libGL.so.1` (Gladio) и `libvulkan_vortek.so` экспортируют одинаковые `RingBuffer_*` / `ArrayBuffer_*`; загрузчик может связать вызовы Vortek с копией Gladio (другие структуры). Теперь `gladio/gladio.map` экспортирует только `gl*` / `glX*` / `__glX*`, остальное скрыто (+ `-Bsymbolic-functions`).

## Gladio: выбор версии и импорт .tzst прямо в приложении (v7)
- Рядом с «Gladio» в настройках графики кнопка теперь активна: окно «Gladio Конфигурация» как у Turnip/Zink — список версий, кнопки «установить» (выбрать .tzst) и «удалить».
- Встроенная версия — `gladio-1.3` (из assets). Импортированные лежат в `files/installed_components/gladio/`. Тип файла определяется по содержимому: libGL с маркером `GLADIO_NO_ERROR` = Gladio, иначе Zink. Файл `gladio-1.4.tzst` импортируется как версия `1.4`.
- Повторный импорт под тем же именем распаковывается заново (в cacheId добавляется время файла). Версия Gladio пишется в `driver-report.txt` (`Gladio version`).
- APK пересобирать нужно один раз (ради этой кнопки), дальше новые сборки Gladio добавляются без пересборки APK.

## Gladio клиент: SIGSEGV в `glDrawArrays` → `writeUnboundVertexArrays` → `memcpy` (v7)
Причина: `glVertexAttribPointer` запоминал сырой адрес отображённого VBO. Когда `glBufferData` увеличивал буфер, он перемапливался (`munmap` старого), адрес в атрибуте оставался висячим. Теперь атрибут хранит id буфера и смещение, адрес берётся при отрисовке; чтение ограничено размером буфера (хвост добивается нулями, формат потока не меняется).

## Gladio клиент: защита от падения в `writeUnboundVertexArrays` + быстрее ArrayBuffer (v8)
- Клиентские массивы вершин проверяются через `mincore()` перед копированием: нечитаемый указатель больше не роняет игру, массив заполняется нулями, а в лог пишется `gladio: unreadable client vertex array base=... offset=... size=...` (первые 30 раз). `GLADIO_SAFE_ARRAYS=0` — выключить проверку.
- Если массив вершин больше своего буфера: `gladio: vertex array larger than its buffer ...`.
- `ENSURE_ARRAY_CAPACITY`: для байтовых массивов (`ArrayBuffer`) рост был «ровно под размер» (realloc на каждую запись) — теперь ×1.5.

## Gladio клиент: GL_ARRAY_BUFFER привязан к контексту, а не к VAO (v9)
Лог v8: `gladio: unreadable client vertex array base=0xc size=280` — смещение VBO принималось за клиентский указатель. Причина: клиент хранил привязки всех буферов внутри VAO, а в OpenGL внутри VAO живёт только `GL_ELEMENT_ARRAY_BUFFER`; `GL_ARRAY_BUFFER` и остальные — состояние контекста. Factorio привязывает VBO до `glBindVertexArray`, после чего `glVertexAttribPointer` не видел буфер. Теперь все цели, кроме element array, хранятся контекстно (`gladio/src/gl_buffer.c`).

## Gladio клиент: диагностика/обход «указателя» < 64 КБ (v10)
v9 не помог: `base=0xc size=280` остался. Теперь `glVertexAttribPointer` с адресом < 65536 без привязанного GL_ARRAY_BUFFER считается смещением в VBO (клиентские данные не читаются) и пишет в лог `gladio: glVertexAttribPointer index=… but no GL_ARRAY_BUFFER bound (ctx=… vao=… program=… last glBindBuffer(ARRAY)=… in ctx …)`. Такой же лог для fixed-function массивов (`gladio: fixed-function array …`).

## Gladio сервер + клиент: общий GL_ARRAY_BUFFER, трассировка (v11)
- Сервер (`gladiolegacy/src/gl_buffer.c`, `gl_renderer.c`, `gl_context.c`): привязки всех буферов, кроме `GL_ELEMENT_ARRAY_BUFFER`, теперь общие на контекст (как в OpenGL и как в клиенте с v9). Раньше `gd_handle_glVertexAttribPointer` считал массив клиентским, если VBO был привязан до `glBindVertexArray`, и не вызывал настоящий `glVertexAttribPointer`. **Требует пересборки APK** (нативная библиотека сервера).
- Клиент: `GLADIO_TRACE=N` (по умолчанию 600) пишет в лог первые N буферных вызовов: `gladio-trace: glBindBuffer/glBufferData/glBufferStorage/glMapBufferRange/glUnmapBuffer/glFlushMappedBufferRange/glVertexAttribPointer/glBindVertexArray ...`. `0` — выключить.

## Gladio клиент: зависание 107 с из-за пакетной отправки (v12)
Лог v11: между `glBufferStorage` и следующим вызовом пауза 107 с. `glBufferStorage` ждал ответ напрямую по сокету (`recv_fds`), а сам запрос лежал в пачке. Исправлено: сброс пачки перед `recv_fds` и перед любой отправкой GLX-запроса по сокету (`glXSwapBuffers` и др. иначе могли опередить команды рисования). Пакетная отправка теперь выключена по умолчанию (`GLADIO_BATCH=32` включает).

## glVertexAttribIPointer + трассировка с хвостом (v13)
- Лог v12: игра вызывает `glVertexAttribIPointer`, он был заглушкой и на клиенте, и на сервере. Теперь реализован (клиент отправляет `REQUEST_CODE_GL_VERTEX_ATTRIB_IPOINTER`, сервер вызывает настоящий `glVertexAttribIPointer`). **Серверная часть → нужна пересборка APK.**
- Трассировка: `GLADIO_TRACE=N` печатает первые N (по умолчанию 120) вызовов сразу, а последние 400 хранит в памяти и печатает как `gladio-tail:` при завершении процесса (например после `XIO fatal IO error`). В трассу добавлены draw-вызовы и `glXSwapBuffers`.

## Ошибки шейдеров видны в logs.txt (v14)
Лог v13: игра дошла до экрана загрузки (Loading mods), затем `Failed to compile program 'pass-through-pos-uv.vert, mipmap-gen.frag'` без причины. Сервер конвертирует GLSL при линковке, а текст ошибки печатал только в отладочной сборке. Теперь `compileShaderObject` / `linkShaderProgram` всегда запоминают лог GLES (`ShaderConverter_lastError`), `glGetShaderInfoLog` / `glGetProgramInfoLog` отдают его игре, а клиент печатает `gladio: glGetProgramInfoLog: ...`. **Серверная часть → пересборка APK.**

## Клиент печатает исходник упавшего шейдера (v15)
Лог v14 (серверный лог дошёл): `fragment shader 30 ... ERROR: 0:36: '+' wrong operand types vec2 + ivec2` / `texelFetch: no matching overloaded function` / `cannot convert const float to vec4` для `mipmap-gen.frag`. Клиент теперь запоминает исходник каждого шейдера и при ошибке печатает его построчно: `gladio-src: NNN| ...`. Только клиент Gladio, **APK пересобирать не нужно**.

## Исходник упавшего шейдера в logs.txt (v15, только клиент Gladio)
Лог v14: `fragment shader 30 (converted for OpenGL ES): ERROR: 0:36: '+' wrong operand types vec2 and ivec2; 'texelFetch' no matching overloaded function; '=' cannot convert from const float to vec4` (`mipmap-gen.frag`). Клиент запоминает исходный GLSL (`glShaderSource`) и при ошибке печатает его построчно как `gladio-src: NN| ...` (до 6 шейдеров). **APK пересобирать не нужно** — только Gladio.

## Конвертер шейдеров: не трогать строки с ivec/uvec/texelFetch (v16)
Лог v15 показал исходник `mipmap-gen.frag` (GLSL 330, вывод SPIRV-Cross). `implicitConvertIntToFloat` видел float-переменную в строке (`gl_FragCoord`, `t1`) и превращал целые: `2`→`2.0`, `_27.offset`→`vec2(_27.offset)`, `0`→`0.0` в `texelFetch`. В GLES это `ivec2 + vec2` и `texelFetch(..., 0.0)`. Теперь строки с `ivec`, `uvec`, `texelFetch`, `textureSize` не конвертируются (`gladiolegacy/src/shader_converter.c`, `lineUsesIntegerTypes`). **Серверная часть → пересборка APK.**

## Конвертер шейдеров: GLSL 330+ без угадывания типов, точность для samplerBuffer (v17)
- Лог v16: `compress-rygDXT.frag` — `float(akku >> 16)` превращалось в `float(akku) >> 16.0`. Теперь `implicitConvertIntToFloat` не применяется к шейдерам `#version >= 330` вообще, а для старых пропускает строки с `ivec/uvec/texelFetch/textureSize/>>/<</uint(/int(/float(`.
- Лог v16: `sprite-treeGrowth.vert` — `uniform samplerBuffer ...: either a default precision should be defined`. Точности `precision highp <sampler>` теперь добавляются в оба типа шейдеров и для всех sampler-типов ES 3.2 (включая `samplerBuffer`, `sampler2DMS`, cube-массивы). **Серверная часть → пересборка APK.**

## Конвертер шейдеров: gl_VertexID и разбор всех 111 шейдеров Factorio (v18)
Разобран архив шейдеров игры: 111 GLSL-шейдеров (73 .frag + 38 .vert), все `#version 330`.
- `gl_VertexID` (22 использования в 17 вершинных шейдерах) ловился как префикс старого `gl_Vertex` и превращался в несуществующий `gd_VertexID` (лог v16: `'gd_VertexID' : undeclared identifier`). Теперь `gl_VertexID` и `gl_InstanceID` не трогаются (`isModernBuiltinName`).
- Для шейдеров `#version >= 330` отключено и угадывание параметров функций (`implicitConvertFunctionParams`: texture/textureLod/textureProj/min/max/pow/mod/clamp/smoothstep), как раньше `implicitConvertIntToFloat`. **Серверная часть → пересборка APK.**

## Сжатые 3D-текстуры + защита загрузки текстур (v19)
Лог v18: шейдеры компилируются, игра грузит моды, SIGSEGV на 50% в `glCompressedTexSubImage2D` → `RingBuffer_write` → `memcpy` (виртуальный атлас 4096x4096x18).
- `glCompressedTexImage3D` / `glCompressedTexSubImage3D` на клиенте были подменой 2D-версий (терялись `depth` и `zoffset`). Теперь это настоящие запросы `TEX_IMAGE3D` / `TEX_SUB_IMAGE3D` с `type == 0` (маркер сжатого объёма); сервер (`request_handler.c`, `decompressTexVolume`) распаковывает каждый слой DXT в BGRA8 и вызывает `glTexImage3D` / `glTexSubImage3D`. **Серверная часть → пересборка APK.**
- Клиент: данные текстуры с нечитаемым указателем больше не роняют процесс (`gl_write_raw_checked`): отправляются нули и пишется `gladio: texture upload (request N) has unreadable data=... size=...`.

## Диагностика загрузки текстур (v20, только клиент Gladio)
Лог v19: падения в `memcpy` нет; `gladio: texture upload (request 159) has unreadable data=(nil) size=32768/65536` (`glCompressedTexSubImage2D`, данные NULL при ненулевом размере — игра, видимо, грузит через PBO со смещением 0, а клиент его не видит), затем игра сама прерывается (`VideoBitmapOpenGL.cpp:267: data.size() == size_t(size) + offset was not true`).
- В трассе каждая загрузка текстуры: `texupload req=… fmt=… WxHxD csize=… data=… pbo=…`.
- При первой подозрительной загрузке в лог сразу печатаются последние 150 вызовов (`gladio-recent:`) и id привязанного `GL_PIXEL_UNPACK_BUFFER`. **APK пересобирать не нужно.**

## Детектор рассинхронизации ответов (v21, только клиент Gladio)
Лог v20 (`gladio-recent`): сначала `glGenBuffers`→87, PBO-загрузка DXT5 с `pbo=87` проходит; затем `glDeleteBuffers 87`, `glGenBuffers 1` и сразу `glBindBuffer ... id=0` — игра получила id 0 (ответ на `glGenBuffers` пустой/не тот: `memcpy(buffers, inputBuffer.buffer, inputBuffer.size)` с размером 0 оставляет 0). После этого загрузка текстуры идёт с `pbo=0`, `data=NULL`, и игра прерывается.
- `gl_recv` сверяет код ответа с кодом последнего запроса (`RingBuffer.lastCode`) и при несовпадении печатает `gladio: reply mismatch: last request N, reply code M (size S)` + последние 80 вызовов.
- `glGenBuffers` печатает `gladio: glGenBuffers(n) got a reply of K bytes`, если ответ не `n*4` байт. **APK пересобирать не нужно.**

## Канал «сервер → logs.txt» (v22)
Лог v21: `reply mismatch` / `got a reply of` не сработали — очередь ответов в порядке, а `glGenBuffers` после `glDeleteBuffers 87` всё равно вернул id 0 (то есть 0 вернул сервер; сообщения сервера уходили только в logcat).
- Сервер (`gladio.h`, `request_handler.c`, `gl_context.c`): `gladioServerLog()` складывает текст, а `gl_send` прикрепляет его к ближайшему ответу (флаг `0x80000000` в размере, перед данными `[uint16 длина][текст]`). Клиент (`gl_recv`) печатает его как `gladio-server: ...`.
- Сервер сообщает: `glGenBuffers(n) returned id 0 ... glError=0x..` (с повторной попыткой), а также первые 40 GL-ошибок после запросов (`glError 0x... after REQUEST_...`, проверяются первые 30000 запросов). **Нужна пересборка APK и Gladio.**

## Исправление сборки v22 (v23)
Сборка APK v22 упала на линковке `libgladiolegacy.so`: `undefined symbol: requestCodeToString` — функция определена только при `DEBUG_MODE` (`request_codes.h`), а я вызвал её в ветке без отладки (`gl_context.c`). Теперь пишется числовой код запроса: `glError 0x... after request code N`.

## Сообщения о неудачной отправке/чтении + приветствие сервера (v24)
Лог v23: `gladio-server:` не появилось вообще, `glGenBuffers` после `glDeleteBuffers 87` снова дал id 0 без сообщений. `GL_RECV_CHECKED`/`GL_SEND_CHECKED` при неудаче молча выходят из функции (переменная игры остаётся нулём) — это совпадает с симптомом.
- Клиент: `gladio: <функция>: the reply could not be read (ring exit flag: N)` и `... request N could not be sent ...` (до 10 раз каждое).
- Сервер: при `glXMakeCurrent` присылает `server channel ok; GLES: <версия> / <GPU>` — проверка канала и настоящий драйвер. **Нужна пересборка APK и Gladio.**

## Жизненный цикл GL-контекстов в логе (v25, только клиент Gladio)
Лог v24: канал сервера работает (`server channel ok; GLES: OpenGL ES 3.2 ... / Adreno (TM) 830`, приветствие пришло дважды — два GL-потока). Сервер сообщил `glError 0x501` (INVALID_VALUE) после запроса 445 (`glTexImage2D`) несколько раз, а затем `glCompressedTexSubImage2D / glGenBuffers / glReadPixels / glGetTexImage: the reply could not be read (ring exit flag: 1)` — серверная нить контекста остановлена (`destroyGLContext` ставит `RING_STATUS_EXIT`), а клиент продолжает им пользоваться. Именно поэтому `glGenBuffers` «возвращает 0».
- Клиент печатает: `create GL context for thread N`, `destroy GL context of thread N`, `glXMakeCurrent thread=N drawable=.. ctx=.. (id ..) threadContext=..`, `glXDestroyContext thread=N ctx=..`, а в ошибках чтения/отправки — `[thread N]`. **APK пересобирать не нужно.**

## Java: кто закрывает соединение / уничтожает GL-контекст (v26)
Лог v25: все GL-вызовы идут из одного потока; контекст 1 (проба) создан и уничтожен штатно, контекст 2 создан, затем при `glCompressedTexSubImage2D` кольцо уже помечено `RING_STATUS_EXIT`. Клиентских `destroy` нет, GLX-запросов на соединении Gladio после 00:45:19 нет => контекст уничтожила Java-сторона.
- В `logs.txt` теперь: `xserver:conn fd=N: connection shutdown / no more data from the peer, closing / request failed, closing: <IOException>` (`XConnectorEpoll`), `xserver:GLX destroyGLContext id=.. fd=..` и `xserver:GLX destroyAllGLContexts fd=.. contexts=..` (`GLXExtension`). **Только APK (Java), Gladio пересобирать не нужно.**

## Java: стек при любом уничтожении GL-контекста (v27)
Лог v26: между `Loading 3D bitmaps: 21` и ошибками чтения закрылись три X-соединения (`xserver:conn fd=196/197/124: connection shutdown`), но `destroyAllGLContexts` / `destroyGLContext` не печатались — Java-сторона контекст не уничтожала (или это делает другой путь).
- `GLXExtension`: все нативные уничтожения идут через `destroyGLContextLogged`, который пишет в `logs.txt` причину и стек вызовов (`xserver:GLX native destroyGLContext ptr=.. reason: .. | ..`); создание: `xserver:GLX createGLContext fd=.. contextId=.. ptr=..`; закрытие клиента: `xserver:GLX client destroyed fd=..`. **Только APK.**

## Метка сборки в driver-report (v28)
Лог v27: ни одной строки `xserver:GLX createGLContext ...` (она есть только в v27) — похоже, запускался APK без этих правок, а `versionCode 34` не меняется. В `driver-report.txt` добавлена строка `Port build: v28 (...)`. Содержимое Java-диагностики то же, что в v27.

## Java-трассировка перенесена в LegacyGLXExtension (v29)
Лог v28: метка `Port build: v28` есть, но `xserver:GLX ...` строк нет — в режиме Gladio X-сервер использует `LegacyGLXExtension` (своя нативная библиотека `gladiolegacy`), а не `GLXExtension`, куда я добавлял логи в v26/v27/v28. Теперь те же сообщения (`GLX createGLContext`, `GLX native destroyGLContext ... | <стек>`, `GLX client destroyed`) есть в `LegacyGLXExtension`. **Только APK.**

## v32: EINTR в X-коннекторе + краш glReadPixels (объединение с работой из другого чата)
**Корневые причины (подтверждены логами):**
1. `xconnector_epoll.c`: `epoll_wait` / `poll` возвращали -1 при `EINTR` (просто пришёл сигнал), цикл выходил, вызывался `killAllConnections` — закрывались ВСЕ X-соединения разом, а с ними GL-контексты Gladio (в логе: три `xserver:conn ... connection shutdown` подряд, затем `GLX native destroyGLContext ... client fd=N gone`). Теперь `EINTR` повторяет вызов (`epollThread`, `waitForSocketRead`).
2. `gl_renderer.c` / `request_handler.c` (патч v31 из другого чата): Factorio сжимает текстуры на GPU (RGBA32UI/RG32UI), читает результат `glReadPixels` в PBO и грузит через `glCompressedTexSubImage2D`. Сервер подменял формат на RGBA/UNSIGNED_BYTE, а при привязанном `GL_PIXEL_PACK_BUFFER` копировал из NULL (SIGSEGV в `memmove`, `GLRenderer_readPixels+508` — убивал процесс приложения). Теперь: кэш чтения не используется с pack-буфером, целочисленные/float-форматы читаются как запрошено (`GLRenderer_readPixelsRaw`) и копируются в общую память буфера, BGRA-перестановка безопасна, утечка кэша исправлена.
- В `driver-report.txt`: `Port build: v32 (...)`. **Нужна пересборка APK (серверная часть + Java). Gladio клиент не менялся (2.9).**
- Не проверено на устройстве: открытым остаётся `glError 0x502` после запроса 159 (`glCompressedTexSubImage2D`) из лога v29 — если после v32 он останется, смотреть путь загрузки из PBO.

## v33: сжатые текстуры без данных + падение на выходе
Лог v32: игра по-прежнему прерывается (`VideoBitmapOpenGL.cpp:267`), а сервер сообщает `glError 0x501` ×9 после запроса 445 (`glTexImage2D`) и `0x502` после 159 (`glCompressedTexSubImage2D`). Logcat при выходе: SIGABRT Scudo `misaligned pointer when deallocating` в `destroyGLXContext` ← `destroyAllGLXContexts` ← `killAllConnections`.
- `texture_utils.h` (`convertTexImageFormat`): `glTexImage2D` со СЖАТЫМ внутренним форматом и без данных (так Factorio выделяет память под атласы) превращался в `internalformat = GL_BGRA` — в ES 3.2 это `INVALID_VALUE`, текстура не создавалась, а следующая `glCompressedTexSubImage2D` получала `INVALID_OPERATION`. Теперь `GL_BGRA8_EXT` (0x93A1, размерный формат из EXT_texture_format_BGRA8888) + `GL_BGRA/GL_UNSIGNED_BYTE`; то же в `glCompressedTexImage2D` и в сжатых 3D-загрузках (`request_handler.c`).
- `glTexImage2D`: при ошибке сервер пишет в лог `glTexImage2D error 0x..: target level WxH border internalformat A->B format A->B type A->B imageSize` (до 12 раз) — если 0x501 останется, будет видно точные параметры.
- `destroyGLXContext` (`gl_context.c`): убран полный разбор CPU-состояния (`GLClientState_destroy` + `GLRenderer_destroy`), который вызывал Scudo-abort при выходе из игры; освобождается только GPU-часть и EGL-контекст. **Серверная часть → пересборка APK; Gladio клиент не менялся (2.9).**

## v37: всё логирование вырезано (основа — v36 из другого чата, где игра заработала)
Функциональные исправления v36 (EINTR, glReadPixels, BGRA8, RGTC, виртуальный атлас и т.д.) не тронуты. Удалено:
- **Клиент Gladio:** трассировка вызовов (`GL_TRACE`, кольцо на 400 записей, `gladio-recent/tail`), дамп исходников шейдеров (`gladio-src`), проверка соответствия ответов (`lastCode`), разбор текста от сервера в заголовке ответа, сообщения о неудачной отправке/чтении, о контекстах и `glXMakeCurrent`, о нечитаемых массивах/загрузках текстур. Защиты (`mincore`, нули вместо падения) остались, но молча.
- **Сервер gladiolegacy:** канал `gladioServerLog` и флаг `0x80000000` в ответах (протокол снова как в оригинале), приветствие сервера, дополнительные `glGetError` после каждого запроса и после `glTexImage2D`, повторная попытка `glGenBuffers`, диагностика `glGetTexImage`/`glGetCompressedTexImage`/`glGetTexLevelParameter`, перенос ошибок шейдеров в `glGet*InfoLog` (`ShaderConverter_lastError`).
- **gladiorenderer:** 10 строк `HANG_DEBUG` в `gl_context.c`.
- **Java:** все вызовы `debugPrint` (27 мест, включая по одному на каждый X-запрос), `XClient.traceRequest` теперь пустой, `XServer.debugPrint` — пустая заглушка, `destroyGLContextLogged`, `debugLog` в `XConnectorEpoll`, строка `Port build` в `driver-report.txt`.
- **Нативные `println` / `debug_printf`** (оригинальные сообщения «unimplemented», ошибки шейдеров и т.п.) отключены одним переключателем в трёх `winlator.h` и в `ring_buffer.c`: `println` → `((void)0)`. Включить обратно: `-DPORT_ENABLE_LOGGING` при сборке.
- Оставлено как есть: оригинальные сообщения об ошибках приложения (`Log.e` / `printStackTrace` в Java, `Diagnostics`, отчёты `driver-report` / `vortek-report` / `compression-report`).
- **Нужна пересборка APK и Gladio клиента (новая версия, например 3.0).**
