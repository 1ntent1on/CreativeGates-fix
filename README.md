# CreativeGates – TheCivia fork

Fork pluginu **CreativeGate** (Tamasoft): portály mezi branami postavenými hráči. Rám z bloků, uvnitř voda nebo portál, brány se stejným jménem sítě (pojmenovaný item z kovadliny) jsou propojené.

## Větve
- `master`: původní kód (Kotlin, Gradle) beze změn, jen tento README.
- `feature/badges-hooks-and-fixes`: naše verze **0.1.2-thecivia**:
  - **Maven build proti Paper 26.3** místo Gradle,
  - **příkazy za konzoli** při vytvoření brány (`createCommands`) a při průchodu (`travelCommands`, `travelCommandsOwnGates`) s placeholdery `{player} {uuid} {network} {gate} {gates} {creator} {creator_uuid}`; hodnoty od hráče se čistí na `[\p{L}\p{N}_-]` (max. 64 znaků), takže nejde připsat vlastní argumenty (napojení na Badges),
  - **bezpečné ukládání `gates.json`**: atomický zápis (`*.tmp` + `ATOMIC_MOVE`), ukládání na pozadí, synchronně při vypnutí.

- `fix/review-0.1.3` (navazuje na předchozí, **verze 0.1.3-thecivia**): opravy z code review: únik paměti ve `Frames`, bezpečnější ukládání (jen při změně, poškozený `gates.json` se přejmenuje), částice místo skutečné exploze při rozbití rámu, volitelné vlastnictví sítě `networkOwnership` (`creativegates.network.bypass`), správná ruka a výdej itemu, index bran podle tvůrce, limit jen při vytvoření, `teleportAsync` do středu bloku s kontrolou průchodnosti, příkazy po průchodu až po příchodu.

## Build
Na větvi `fix/review-0.1.3` (nebo `feature/badges-hooks-and-fixes` pro 0.1.2):
```
mvn clean package
```
