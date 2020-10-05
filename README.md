# Server Curse Manager
A Combination of cpw/cursepacklocator and cpw/serverpacklocator.

## Structure
### On the server
On server start the mod locator loads its data from the `serverpack` folder. This folder contains the server private key and certificate for HTTP auth and a `pack.json` file containing a list of all used mods. The mods are either from curseforge, identifed by ist project and fileid, or a local file identified by its path.
If the file is pressent the mods are downloaded into the `servermods` folder.
There is also a zip file generated that get send to the client on request.

### On the client
The client loads ist config and certificate files from the `serverpack` folder. If the configuration is correct, the client connect to the server using HTTPS and client side auth, like its done in serverpacklocator. 
its requesting the pack file and including its current hash (or "0") in the http get request. The server either returns the pack, or return the status code 304 (Not modified).
If the client gets a new version ist saves it in the `serverpack` folder as `modpack.zip` and unpacks/installs it.
Whie downloading the mods the cleint creats a lookup table fileid -> orriginal name.