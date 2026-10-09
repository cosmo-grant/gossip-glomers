#!/bin/sh
cd "$(dirname "$0")/.."
exec lein with-profile -dev run -m gossip-glomers.echo
# exec bb -m gossip-glomers.echo
