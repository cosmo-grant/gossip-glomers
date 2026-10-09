#!/bin/sh
cd /home/cosmo-grant/git/personal/gossip-glomers
exec lein with-profile -dev run -m gossip-glomers.echo
