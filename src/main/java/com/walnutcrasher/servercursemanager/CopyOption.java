package com.walnutcrasher.servercursemanager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Predicate;

public enum CopyOption {
	OVERWRITE("overwrite", path -> {
		return true;
	}),
	KEEP("keep", path -> {
		return !Files.exists(path);
	});

	private final String configName;
	private final Predicate<Path> writeFilePred;

	private CopyOption(String name, Predicate<Path> writeFilePred) {
		this.configName = name;
		this.writeFilePred = writeFilePred;
	}

	public boolean writeFile(Path path) {
		return this.writeFilePred.test(path);
	}

	public String configName() {
		return this.configName;
	}

	public static CopyOption getOption(String copyOption) {
		if(Utils.isBlank(copyOption)) {
			throw new IllegalArgumentException("copy name is empty");
		}

		return Arrays.stream(CopyOption.values())
				.filter(opt -> opt.configName.equals(copyOption))
				.findAny()
				.orElseThrow(() -> new IllegalArgumentException("unkown copy option"));
	}

}
