# CreativeGates – TheCivia fork

Fork pluginu **CreativeGate** (Tamasoft): portály mezi branami postavenými hráči. Rám z bloků, uvnitř voda nebo portál, brány se stejným jménem sítě (pojmenovaný item z kovadliny) jsou propojené.

## Větve
- `master`: původní kód (Kotlin, Gradle) beze změn, jen tento README.
- `feature/badges-hooks-and-fixes`: naše verze **0.1.2-thecivia**:
  - **Maven build proti Paper 26.3** místo Gradle,
  - **příkazy za konzoli** při vytvoření brány (`createCommands`) a při průchodu (`travelCommands`, `travelCommandsOwnGates`) s placeholdery `{player} {uuid} {network} {gate} {gates} {creator} {creator_uuid}`; hodnoty od hráče se čistí na `[\p{L}\p{N}_-]` (max. 64 znaků), takže nejde připsat vlastní argumenty (napojení na Badges),
  - **bezpečné ukládání `gates.json`**: atomický zápis (`*.tmp` + `ATOMIC_MOVE`), ukládání na pozadí, synchronně při vypnutí.

## Další opravy (zatím mimo repozitář)
Verze **0.1.3-thecivia** z code review (únik paměti ve `Frames`, skutečná exploze při rozbití rámu, únos sítě `networkOwnership`, ruce a výdej itemu, index bran podle tvůrce, limit jen při vytvoření, bezpečný `teleportAsync` do středu bloku…) je postavená jako samostatný Maven projekt mimo tento repozitář. Příkazové hooky v ní odpovídají větvi výše. Před nasazením ji převést do repozitáře jako novou větev.

## Build
Na větvi `feature/badges-hooks-and-fixes`:
```
mvn clean package
```
