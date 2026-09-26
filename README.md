# mcscenario

Headless scenario tests for modded Minecraft servers. A scenario is a YAML file that starts your dev server, runs commands on a tick schedule, restarts it, edits the world on disk between runs, and asserts on what the server logs. It exits non-zero on failure, so it drops straight into CI.

It's for bugs that unit tests can't reach: state that goes wrong across a save/reload, chunk unload, or lost `SavedData` file.

## Example

```yaml
name: create-logistics-desync
server:
  command: ["./gradlew", "--no-daemon", "runServer"]
  runDir: run/server
  packFormat: 48                  # Minecraft 1.21.1
  runTimeout: 15m
steps:
  - resetWorld: true
  - run:
      name: setup
      phases:
        - after: 1s
          commands:
            - setblock 1007 11 1000 create:stock_link[face=floor,facing=north]
      hold: 10s
  - deleteWorldFile: data/create_logistics.dat
  - run:
      name: reload
      hold: 30s
probes:
  unloaded: 'unloaded=(?<value>-?\d+)'
assertions:
  - run: reload
    probe: unloaded
    all: ">= 0"
```

The full version is in [scenarios/create-logistics-desync](scenarios/create-logistics-desync).

## Usage

Requires Java 21.

```bash
./gradlew installDist
build/install/mcscenario/bin/mcscenario validate scenario.yaml
build/install/mcscenario/bin/mcscenario run scenario.yaml --work-dir /path/to/your/mod --out results
```

Exit codes: `0` passed, `1` an assertion or run failed, `2` invalid scenario or usage, `3` the scenario could not run. Each run's full server log and a JSON report are written to `--out`.

## How it works

Before each run, mcscenario writes a datapack into the world whose `#minecraft:load` function runs your phases with `schedule function`, then runs `save-all flush` and `stop`. Stopping from inside the game means saved data is written exactly as it would be for a player. It also means commands reach the server even when it runs under Gradle, which doesn't forward stdin. The pack is removed when the scenario ends.

`server.properties` gets `function-permission-level=4` (so the pack can run `stop`), `level-name`, and anything under `server.properties`. `eula=true` is written only if the scenario sets `acceptEula: true`.

## Scenario reference

| Key | Meaning |
| --- | --- |
| `server.workDir` | Where `command` runs, relative to the scenario file. `--work-dir` overrides it. |
| `server.command` | Launch command. `./gradlew` resolves to `gradlew.bat` on Windows. |
| `server.runDir` | Server run directory, relative to `workDir`. |
| `server.levelName` | World folder name (default `world`). |
| `server.packFormat` | Data pack format of the target Minecraft version. |
| `server.runTimeout` | Per-run limit before the process tree is killed. |
| `server.properties` | Entries merged into `server.properties`. |
| `server.environment` | Extra environment variables. |
| `steps[].run` | `name`, `phases` (each has `after` and `commands`), and `hold`. Durations take `t` (ticks), `s` or `m`. |
| `steps[].deleteWorldFile` | Path inside the world folder to delete between runs. |
| `steps[].resetWorld` | `true` deletes the world so the next run generates a new one. |
| `probes` | Name → regex applied to every log line. A `value` named group is parsed as a number. |
| `assertions` | `run`, `probe`, and one of `all`, `any`, `none` (on values) or `count`, each a comparison such as `">= 0"`. `all` fails if nothing matched. |

## License

MIT
