# PlayerProgress 2.0

Плагин прокачки познаний для Purpur 26.2 (Java 25).

## Сборка через GitHub (без программ, только браузер)

1. Зарегистрируйтесь на github.com и нажмите **New repository** (можно Private).
2. На странице пустого репозитория нажмите **uploading an existing file** и перетащите туда
   папку `src`, файлы `pom.xml` и `README.md` из архива. Нажмите **Commit changes**.
3. Нажмите **Add file → Create new file**. В поле имени впишите ровно
   `.github/workflows/build.yml`, вставьте содержимое файла `build.yml` из архива
   (папка `.github/workflows`) и нажмите **Commit changes**.
4. Откройте вкладку **Actions** — сборка запустится сама (2–4 минуты).
   Если она не стартовала: выберите *Build PlayerProgress* → **Run workflow**.
5. Когда появится зелёная галочка, откройте сборку, внизу в разделе **Artifacts**
   скачайте `PlayerProgress`. Внутри zip лежит `PlayerProgress-2.0.0.jar`.

Если сборка красная — откройте её, раскройте шаг **Build** и пришлите текст ошибки.

## Установка

1. Удалите старый `PlayerProgress-1_0_0.jar` из `plugins`.
2. Положите новый jar в `plugins` и перезапустите сервер.
3. Старый `config.yml` автоматически переименуется в `config-old.yml`, а уровни игроков
   перенесутся из старой базы (очки старой версии не переносятся).

## Усилители (ItemsAdder)

Усилители — это предметы ItemsAdder. Создайте их в ItemsAdder с ID из раздела
`boosters` конфига (по умолчанию `playerprogress:booster_attack` и т. д.) или
впишите в конфиг ID своих предметов. Выдавать: `/pp give <игрок> <усилитель> [кол-во]`,
`/iagive`, или класть в сундуки своих структур.

## Команды

| Команда | Описание |
|---|---|
| `/pp menu` (`/pp m`) | Меню прокачки |
| `/pp top [познание\|total]` | Топ игроков |
| `/pp progress <игрок> <познание> <set\|add\|take> <значение>` (`/pp p`) | Изменить уровень |
| `/pp points <игрок> <познание> <set\|add\|take> <значение>` | Изменить очки |
| `/pp give <игрок> <усилитель> [кол-во]` | Выдать усилитель |
| `/pp admin` (`/pp a`) | Админ-меню |
| `/pp reload` (`/pp r`) | Перезагрузить конфиг |

## Права

| Право | По умолчанию | Описание |
|---|---|---|
| `playerprogress.menu` | все | Меню прокачки |
| `playerprogress.top` | все | Топы |
| `playerprogress.admin` | OP | Админ-команды и меню |
| `playerprogress.booster.<ключ>` | все | Использовать усилитель (`attack`, ..., `random`) |
| `playerprogress.<познание>.*` | никто | Доступ ко всему, как на максимальном уровне |
| `playerprogress.<познание>.<N>` | никто | Доступ как на уровне N (`playerprogress.mining.16`) |
| `playerprogress.bypass` | никто | Полностью игнорировать ограничения |

## Плейсхолдеры (PlaceholderAPI)

- `%playerprogress_<познание>_level%` — уровень
- `%playerprogress_<познание>_points%` — очки
- `%playerprogress_<познание>_required%` — очки для следующего уровня
- `%playerprogress_<познание>_xp%` — уровни опыта для следующего уровня
- `%playerprogress_<познание>_name%` — название познания
- `%playerprogress_total_level%` — сумма уровней
- `%playerprogress_top_<познание|total>_<1-10>_name%` — ник на месте N
- `%playerprogress_top_<познание|total>_<1-10>_value%` — значение на месте N
- `%playerprogress_rank_<познание|total>%` — место игрока

Познания: `attack`, `defense`, `agility`, `magic`, `building`, `gathering`, `mining`, `farming`.
Топы пересчитываются раз в `settings.top-update-minutes` минут.
