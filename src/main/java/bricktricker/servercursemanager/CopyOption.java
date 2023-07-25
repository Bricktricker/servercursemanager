package bricktricker.servercursemanager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Predicate;

public enum CopyOption {
	OVERWRITE("overwrite", path -> {
		// Only overwrite file, if there is not 'path.bak' file present
		String bak = path.toString() + ".bak";
		return !Files.exists(Path.of(bak));
	}),
	KEEP("keep", path -> {
		return !Files.exists(path) && OVERWRITE.writeFile(path);
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
		if(copyOption.isBlank()) {
			throw new IllegalArgumentException("copy name is empty");
		}

		return Arrays.stream(CopyOption.values())
				.filter(opt -> opt.configName.equals(copyOption))
				.findAny()
				.orElseThrow(() -> new IllegalArgumentException("unkown copy option"));
	}

}
