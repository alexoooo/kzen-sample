# kzen-sample-plugin
Example plugin for Kzen

For a local snapshot build, publish `kzen-lib` first and then publish
`:kzen-auto-plugin` from the `kzen-auto` build before running Maven here. The
plugin API now exposes the shared data-contract and value-access types, so its
`kzen-lib-common-jvm` dependency is resolved transitively from Maven Local.
