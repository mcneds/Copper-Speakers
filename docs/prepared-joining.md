# Prepared joining removed

The prepared joining controller, `/copperspeakers sync` commands, generated suffix audio and login pack preloading have been removed. The former file-based control interface is no longer read or written.

Old `config/copper-speakers/tails/` and `config/copper-speakers/joining/` directories can be deleted. Preserve original recordings and ordinary music resource packs. Move any original recordings out of `joining/sources/` before deleting `joining/`.

Players connecting or changing dimensions during a song wait for the next song or a fresh disc insertion. See [current joining behavior](late-listeners.md).
