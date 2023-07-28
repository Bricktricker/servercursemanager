# Server Curse Manager
A feature extension of [cpw/serverpacklocator](https://github.com/cpw/serverpacklocator/).
This project allows a MinecraftForge server owner to specify the modpack with a JSON config file and let clients downlaod it during startup. Makes setting up and updating a custom modpack with friends much easier.

## Server
### Install
Simply put the jar file into the `mods` folder of your MinecraftForge server. Then start the server once to generate the config files and folders.

### Config
Server Curse Manager adds two new folders to your MineraftForge server: `serverpack` and `servermods`. `Servermods` is managed by the program and stores all downloaded mods. The `serverpack` folder contains the configuration files for the Server Curse Manager.

After the first start you should have a `pack.json` file in your `serverpack` folder to configer the loaded mods. This JSON file contains 3 main entries:

1. `port`: The port that Server Curse Manager should use to listen for clients that want to download the modpack
2. `mods`: This is an array that list all mods you want to load on the server and all clients. Every mod is a JSON object with at least 2 values: 
	1. `source`:  The source of the mod, this can either be `curse` to download the mod from [curseforge.com](https://www.curseforge.com/) or `local` to load a local jar file.
	2. If the source is `curse`, you need to specify two more entries: `projectID` (The curseforge project ID of the mod) and `fileID` (The file ID of the specific file).
	3. If the source is `local` you need a `mod` entry specifying  the path to the jar file relative to the MinecraftForge server root folder.
	4. You can also specify aditional entries like the mod name to better organize your config file. Other entries are ignored by Server Curse Manager.
3. `additional`:  Contains additonal files or folders you want to sync to the client, this can be config files, resource packs or client-only mods. Every file or folder you want to sync is a JSON object with two entries:
	1. `file`: The path to the file or folder you want to sync, relative to the  MinecraftForge root folder.
	2. `target`: Where the file or folder should be placed on the client relative to the current game folder (This is either the `.minecraft` folder or the folder specified fo the current profile).
4. `copyOption`: Can either be `overwrite` or `keep`, defaults to `keep`. This specifies how to deal with additional files, if they are already present on the client. You can also specify this for individual files.

Make sure to restart the server after chainging the config file.

### Example config
```JSON
{
    "port": 4148,
    "additional": [
        {
            "file": "config/",
            "target": "config/"
        },
        {
            "file": "clientstuff/OurTexturePack.zip",
            "target": "resourcepacks/texturePack.zip",
            "copyOption": "keep"
        }
    ],
    "mods": [
        {
            "name": "Just Enough Items (JEI)",
            "projectID": 238222,
            "fileID": 4405393,
            "source": "curse"
        },
        {
            "name": "The One Probe",
            "projectID": 245211,
            "fileID": 4159743,
            "source": "curse"
        },
        {
            "source": "local",
            "mod": "serverpack/myMod.jar",
            "name": "MyMod"
        }
    ]
}
```

### Authentication
Server Curse Manager uses the newly added key pairs every Mojang account has to validate the used player UUID that is used to request the modpack. If you only want to allow specific players to download the modpack, you can simply enable the white-list on the server and add all trusted users to it. Server Curse Manager  automatically checks for the enable white-list and only allows players that are on the white-list to download the modpack.

## Client
### Install
Simply put the jar file into the `mods` folder. Then start Minecraft once to generate the config file and needed folders. Make sure you have migrated your Mojang account to a Microsoft account.

### Config
The only important config file is the the `serverpack/config.toml` file.  Here you need to specify the server ip or adress and port of the target MinecraftForge server, right next to the `remoteServer`. You should specify the server in the format `server:port`, e.g. `localhost:8080` or `my.server.com:4148`.
The server alllows overwriting and creating additional files on the client. If you, as the client, don't want a specific file to be created, create an empty file at the same place with the same name, but with an .bak extension. This tells the SCM to skip creating or updating the file.

