# gossip glommers in clojure

Working through [gossip glommers](https://fly.io/dist-sys/) in Clojure.

## Set Up

- dependencies:
  - babashka
  - java + clojure + leiningen
  - gnuplot + graphviz (for maelstrom)
- clone this repo
- get latest maelstrom: https://github.com/jepsen-io/maelstrom/releases
- `tar -xzf maelstrom.tar.bz2` (expect it to be at `./maelstrom/*` )

## Running a Challenge

```
bb eval <name> [--mode maelstrom|direct] [--runner bb|lein]
```

Defaults: `--mode maelstrom --runner bb`.

- `bb eval echo`
- `bb eval echo --runner lein`
- `bb eval echo --mode direct <<< '{"src":"c1","dest":"n1","body":{"type":"init","msg_id":1}}'`

To add a challenge, add an entry to `challenges.edn`.

