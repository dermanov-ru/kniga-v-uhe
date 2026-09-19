# Книга в ухе

Нативный Android-плеер для аудиокниг с knigavuhe.org: поиск и каталог внутри приложения,
скачивание глав, офлайн-воспроизведение через Media3, отдельная позиция у каждой книги,
таймер сна.

Личный проект, не для публикации в сторах: сайт помечает книги как `downloadable: false` и
`offline_allowed: false`, приложение эти флаги не соблюдает.

## Сборка

```bash
./gradlew :app:assembleDebug
```

APK — `app/build/outputs/apk/debug/app-debug.apk`. Требуется JDK 17+, Android SDK 37,
AGP 9.4 (Kotlin встроен в плагин, отдельный `kotlin-android` не подключается).

Тесты парсеров:

```bash
./gradlew :app:testDebugUnitTest
```

## Как устроен сайт

### Данные книги

```
GET https://knigavuhe.org/ajax/book_data/<bookId>/
```

Без авторизации и кук. Ответ — массив из двух элементов: `[ошибка, полезная нагрузка]`,
то есть разбирать надо `[1].result`. Ошибка выглядит как
`[{"code":404,"message":"Route not found"}, null]`.

Внутри `result.init_data`:

- `book` — `id`, `name`, `url`, `cover`, `downloadable`, `offline_allowed`, `blocked`.
  Поля `authors` / `readers` приходят объектом, но у книги без авторов вырождаются в пустой
  массив, поэтому имена берём из `player_data` трека, где они уже строки.
- `playlist` — главы: `id`, `title`, прямой `url` на mp3, `duration_float`, `error`.
- `merged_playlist` — те же главы, склеенные в файлы по ~2 часа, с `from_id`/`to_id`.
  Приложение их не использует: границы глав в склейке считаются суммой длительностей и
  накапливают расхождение.
- `covers` — обложка в полном размере.
- `result.player_data.speed_levels` — набор скоростей, который показывает сам сайт.

### Ссылки на аудио протухают

У каждого трека `player_data.url_refresh = true` и `url_refresh_in ≈ 236 000` секунд (около
66 часов): хэш в пути mp3 периодически меняется. Уже скачанные файлы это не трогает, но
ссылки в базе устаревают. Логика в приложении:

- `BookRepository.refreshUrlsIfStale` перезапрашивает `book_data`, если ссылкам больше суток;
- загрузчик на ответ 403/404/410 обновляет ссылки один раз и повторяет главу;
- плеер на `onPlayerError` делает то же самое и продолжает с той же секунды.

### Поиск и каталог

JSON-API нет, страницы парсятся Jsoup (`CatalogScraper`). `/search/?q=…&page=N`, а также
`/author/<slug>/`, `/reader/<slug>/`, `/genre/<slug>/` — у всех одинаковая разметка карточки
`div.bookkitem`.

Идентификатор книги не всегда есть в адресе: бывает `/book/5230-slug/`, а бывает
`/book/<slug>/` без числа. Во втором случае id достаётся со страницы регуляркой по
`new BookPlayer(<id>` или `cur.book = {"id":<id>`.

### Аудио

Обычные mp3, `accept-ranges: bytes`, Referer и куки не проверяются. Докачка идёт через
`Range`, файл пишется в `<id>.mp3.part` и переименовывается только после полной передачи.

### Шторка

Уведомление с обложкой, названием главы, паузой и перемоткой по главам рисует Media3, канал
`playback`. Ловушка: `MediaSessionService.onGetSession` вызывается только когда к сессии
подключается `MediaController`. В этом приложении контроллера нет — UI работает с плеером
напрямую, — поэтому сессию надо регистрировать руками через `addSession(session)` в `onCreate`,
иначе сервис о ней не знает и уведомления не будет.

Шаг перемотки для кнопок в шторке и на гарнитуре задаётся у самого плеера
(`setSeekBackIncrementMs` / `setSeekForwardIncrementMs`), иначе он разойдётся с кнопками
внутри приложения.

## Что где лежит

| Путь | Зачем |
|---|---|
| `data/remote/KnigavuheApi.kt` | `book_data`, резолв id, поиск |
| `data/remote/CatalogScraper.kt` | разбор карточек листинга |
| `data/db/` | Room: книги, главы, позиция по каждой книге |
| `data/repo/BookRepository.kt` | импорт, обновление ссылок, прогресс |
| `download/DownloadWorker.kt` | скачивание в три потока, докачка, foreground-уведомление |
| `playback/PlaybackController.kt` | единственный ExoPlayer, позиция, скорость, таймер сна |
| `playback/PlaybackService.kt` | MediaSession: шторка, экран блокировки, кнопки гарнитуры |
| `ui/` | Compose: библиотека, поиск, книга, плеер, мини-плеер |

Файлы книг — в `Android/data/dev.mark.knigavuhe/files/books/<bookId>/<chapterId>.mp3`.
