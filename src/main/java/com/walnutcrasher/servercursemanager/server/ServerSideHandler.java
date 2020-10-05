package com.walnutcrasher.servercursemanager.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.walnutcrasher.servercursemanager.SideHandler;

import cpw.mods.forge.serverpacklocator.server.ServerCertificateManager;

public class ServerSideHandler extends SideHandler {
	
	private ServerCertificateManager certManager;
	
	public ServerSideHandler(Path gameDir) {
		super(gameDir);
	}

}
