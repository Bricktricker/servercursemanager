# Server Curse Manager
A Combination of [cpw/cursepacklocator](https://github.com/cpw/cursepacklocator) and [cpw/serverpacklocator](https://github.com/cpw/serverpacklocator/).

## Structure
### On the server
On server start the mod locator loads its data from the `serverpack` folder. This folder contains the server private key and certificate for HTTP auth and a `pack.json` file containing a list of all used mods. The mods are either from curseforge, identifed by the project id and the file id, or a local file identified by its path.
If the file is pressent the mods are downloaded into the `servermods` folder.
The `pack.json` file has to contain a `mods` array containing all modpack mods. Every entry has to be a JSON object containing a `source` entry, with either `curse` of `local` as a value.
If the source is `curse` the mod is fetched from [curseforge.com](https://www.curseforge.com/). There also need to be an `projectID` entry as a Integer with the curseforge mod id and an `fileID` entry as an Integer specifying the curseforge file id.
If the source is `local` a local mod file gets included in the modpack. A `mod` entry as aString is needed to specify the file location relative to the server root folder.
Additionally a `additional` entry can be present in the `pack.json` containing a list of additional files that should be send to the client. Every entry need to be an object containing a `file` entry as a String specifying the file location relative to the server root location. This can also point to a folder. A `target` entry is also needed specifying the target location of the file or folder relative to the gameDir folder (typically the `.minecraft` folder). If the `file` antry points to a folder the `target` entry needs to end in a `/`.
Additionally a `copyOption` entry can be present in the `pack.json` containing a String specifying what sould happen if a file specified as an additional file is allready present. Valid options are `overwrite` to overwrite the existing file and `keep` to keep the existing file. Defaults to `overwrite`.

### On the client
The client loads ist config and certificate files from the `serverpack` folder. If the configuration is correct, the client connect to the server using HTTPS and client side auth, like its done in serverpacklocator. 
its requesting the pack file and including its current hash (or "0") in the http get request. The server either returns the pack, or return the status code 304 (Not modified).
If the client gets a new version ist saves it in the `serverpack` folder as `modpack.zip` and unpacks/installs it.
Whie downloading the mods the cleint creats a lookup table fileid -> orriginal name.