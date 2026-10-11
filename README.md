# Vehicle Catalog — HUD-style redesign

Minecraft Forge 1.20.1 / Forge 47.2.0 / Java 17.

Интерфейс каталога оформлен как индустриальный HUD: список техники расположен слева, 3D-просмотр — по центру, характеристики — справа.

### Что сделано

- список техники с поиском по названию/ID и прокруткой;
- выбор нации и категории в отдельных центральных окнах с подтверждением выбора в основном списке;
- одновременный просмотр 3D-модели и её доступных характеристик;
- конфигурации `sbw/vehicles` читаются из ресурсов загруженных модов (`assets` и `data`), включая SuperbWarfare и аддоны;
- JSON-конфиги техники автоматически читаются из ресурсов запущенной игры и файлов всех загруженных модов/аддонов;
- общий список характеристик показывается для каждой записи; отсутствующие поля отмечаются как «не указано»;
- поля модели, текстуры, интерфейса и другие визуальные/позиционные поля исключены из списка боевых характеристик;
- техника сгруппирована по нации и источнику, а известные машины FCP получают русские названия;
- список можно прокручивать колесом или перетаскиваемым ползунком;
- окно 3D-просмотра расширено влево за счёт более узкого списка техники; модель при открытии показывается с увеличением в 1,5 раза;
- настоящий 3D-рендер зарегистрированной сущности;
- перетаскивание модели левой кнопкой для вращения, правой кнопкой — для сдвига центра; колесо мыши меняет масштаб;
- прокрутка списка и характеристик колёсиком мыши в соответствующих панелях;
- сохранена кнопка открытия каталога из Creative Inventory.

Класс определяется по идентификатору и названию модели, а не по общему техническому полю `Type` конфига. Если класс нельзя определить надёжно, техника попадает в «Прочая техника». Характеристики, отсутствующие в конфигурации, не подставляются.

## Сборка

Проект рассчитан на Java 17, Minecraft 1.20.1 и Forge 47.2.0.
На Windows:

```text
gradlew.bat build
```

Для отображения техники должны быть установлены SuperbWarfare и нужные аддоны. Параметры берутся из их активных JSON-конфигураций при открытии каталога; значения не хардкодятся в каталоге.


## Vehicle nation and class overrides (FCP / AshVehicle / VVP)

The catalog now applies curated real-world identity rules before generic ID heuristics for the vehicle packs FCP, AshVehicle, and VVP. This includes nation corrections for US, Russian/Soviet, Ukrainian, German, British, Swedish, Italian, Australian, Chinese, Japanese, French, Polish, Turkish, South Korean and multinational European systems, plus class corrections for tanks, IFVs, BMPTs, APCs, air-defense vehicles, artillery/MLRS, anti-tank systems, helicopters, aircraft, naval vessels and transport.

The game can contain more than one definition of the same model (for example, FCP single-crew and multicrew definitions); duplicate IDs are intentionally represented once in the catalog. Vehicle class and nation are identified from the real model name/ID where known. Unidentified fictional/ambiguous entries may still fall back to the config's Nation/Country fields or the generic class. The `Faction` field is deliberately not treated as country of origin, because an in-game faction does not necessarily match the vehicle's design/manufacturing nation.
