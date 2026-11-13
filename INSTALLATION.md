# Установка EconomicMoonEclips 1.7.0

## Требования
- Paper 1.21.4
- Java 21
- Vault 1.7.3
- EssentialsX 2.22.x с включённой экономикой
- LuckPerms 5.5.71

## Получение JAR
1. Открой вкладку Actions репозитория.
2. Выбери последний запуск Build plugin.
3. Убедись, что запуск завершился успешно.
4. Скачай артефакт EconomicMoonEclips-1.7.0.
5. Внутри ZIP будет EconomicMoonEclips-1.7.0.jar.

## Установка
1. Останови сервер.
2. Помести JAR в папку plugins.
3. Убедись, что Vault и EssentialsX установлены.
4. Запусти сервер один раз, чтобы создать конфиги.
5. Настрой ранги в LuckPerms.
6. Перезапусти сервер после изменения конфигов.

## Ранги LuckPerms
Выдай соответствующее право группе:
- economicmooneclips.rank.stone
- economicmooneclips.rank.silver
- economicmooneclips.rank.gold
- economicmooneclips.rank.diamond
- economicmooneclips.rank.crystal
- economicmooneclips.rank.eclipse

Множители продажи: STONE ×1; SILVER ×1.25; GOLD ×2; DIAMOND ×2.5; CRYSTAL ×3; ECLIPSE ×4.
Слоты аукциона: 6, 7, 8, 9, 10 и 11 соответственно.

## Команды
- /sell — открыть меню продажи
- /sell all — запросить подтверждение массовой продажи
- /sell all confirm — подтвердить массовую продажу
- /ah — открыть аукцион
- /ah sell <цена> — выставить предмет из основной руки
- /ah search <текст> — искать лоты
- /ah sort asc|desc — сортировка по цене
- /top — топ балансов
- /pmoney <ник> <количество> — выдать деньги (администратор)
- /epmoney <ник> <количество> — снять деньги (администратор)

## Важно
Не ставь development JAR на основной сервер до прохождения тестов на отдельном Paper 1.21.4 сервере. После установки проверь консоль и сценарии продажи, покупки, возврата лотов, рестарта и отключения игроков.
