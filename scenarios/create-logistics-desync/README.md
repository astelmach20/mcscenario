# create-logistics-desync

`GlobalLogisticsManager.getUnloadedLinkCount` returns `totalLinks.size() - loadedLinks.size()`. The two sets are persisted in different places — `totalLinks` in `world/data/create_logistics.dat`, and each link's `Added` flag in its own chunk — and `linkAdded` runs at most once per link, so once they disagree they never re-converge. The count goes negative, and `FactoryPanelBehaviour` (`shouldWait = unloadedLinkCount > 0`) stops waiting for the network to load.

The scenario places a packager, a stock link and a stocked chest; saves and stops; deletes `create_logistics.dat`; then restarts and watches the count for 30 seconds.

## Running

The count isn't observable from outside the mod, so this scenario needs a 4-line logging patch:

```bash
cd /path/to/Create            # branch mc1.21.1/dev
git am /path/to/mcscenario/scenarios/create-logistics-desync/instrumentation.patch
mcscenario run /path/to/mcscenario/scenarios/create-logistics-desync/scenario.yaml --work-dir .
```

The run directory needs `eula.txt` accepted already, or set `acceptEula: true` in the scenario.

## Expected result

Unpatched Create fails `all values >= 0`: every tick after the restart reports `unloaded=-1`. With the fix — `linkLoaded` also adding the position to `totalLinks` — every tick reports `unloaded=0` and the scenario passes.
