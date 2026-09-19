# Книга в ухе — iOS

Порт андроид-плеера на SwiftUI: библиотека, поиск с фильтром по чтецу, скачивание глав,
офлайн-воспроизведение, позиция у каждой книги, таймер сна, управление с экрана блокировки.

Кода общего с андроидом нет — переехало только знание сайта. Разбор API в
[README андроид-проекта](../kniga-v-uhe/README.md): эндпоинт `/ajax/book_data/<id>/`, ответ
массивом `[ошибка, данные]`, ротация ссылок раз в ~66 часов, две разметки листинга
(`div.bookkitem` на десктопе, `span.bookkitemm` на мобильном хосте), резолв id книги.

## Сборка

Собирается только на macOS. Рабочая машина — `macos-ci` (macOS 15.6.1, Apple Silicon,
Xcode 26.3, iOS SDK 26.2).

```bash
rsync -az --delete kniga-v-uhe-ios/ macos-ci:/var/root/build/kniga-v-uhe-ios/ --exclude build
ssh macos-ci 'cd /var/root/build/kniga-v-uhe-ios && /var/root/build/tools/xcodegen/bin/xcodegen generate'
```

Проект держится в `project.yml`, `.xcodeproj` генерируется XcodeGen'ом и в репозиторий не кладётся.
Homebrew на той машине сломан (старый master без curl-шима) и под root не запускается, поэтому
XcodeGen лежит распакованным релизом в `/var/root/build/tools/xcodegen/`.

Тесты на симуляторе:

```bash
ssh macos-ci 'cd /var/root/build/kniga-v-uhe-ios && xcodebuild -scheme KnigaVUhe \
  -destination "id=<UDID симулятора>" CODE_SIGNING_ALLOWED=NO test'
```

Неподписанный `.ipa` под устройство:

```bash
ssh macos-ci 'cd /var/root/build/kniga-v-uhe-ios && xcodebuild -scheme KnigaVUhe \
  -configuration Release -sdk iphoneos -destination "generic/platform=iOS" \
  -derivedDataPath build-device CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build'
# затем .app кладётся в Payload/ и зипуется в .ipa
```

## Подпись

На `macos-ci` сертификатов нет (`security find-identity -v -p codesigning` — ноль), профилей нет,
устройств не подключено. Поэтому сборка отдаёт неподписанный `.ipa`, а подпись делается на стороне:

- **AltStore / SideStore** — ставит `.ipa` своим Apple ID, переподписывает раз в 7 дней;
- **Apple Developer Program ($99/год)** — сертификат кладётся в связку на маке,
  `security unlock-keychain` перед сборкой, дальше TestFlight или ad-hoc.

## Чем отличается от андроида

| Что | Android | iOS |
|---|---|---|
| Плеер | Media3 / ExoPlayer | AVQueuePlayer + MPNowPlayingInfoCenter |
| Загрузки | WorkManager, 3 потока | background `URLSession`, 3 соединения |
| База | Room | JSON-файлы в Application Support |
| HTML | Jsoup | SwiftSoup |
| Сеть | OkHttp | URLSession |

Файлы книг: `Application Support/KnigaVUhe/books/<bookId>/<chapterId>.mp3`, вся папка помечена
`isExcludedFromBackup` — иначе сотни мегабайт уехали бы в iCloud-бэкап.
