package org.cryptomator.common.mountpoint;

import org.cryptomator.common.Environment;
import org.cryptomator.common.settings.VaultSettings;
import org.cryptomator.common.vaults.Volume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

class TemporaryMountPointChooser implements MountPointChooser {

	private static final Logger LOG = LoggerFactory.getLogger(TemporaryMountPointChooser.class);
	private static final int MAX_TMPMOUNTPOINT_CREATION_RETRIES = 10;

	private final VaultSettings vaultSettings;
	private final Environment environment;
	private final IrregularUnmountCleaner cleaner;
	private volatile boolean clearedDebris;

	@Inject
	public TemporaryMountPointChooser(VaultSettings vaultSettings, Environment environment, IrregularUnmountCleaner cleaner) {
		this.vaultSettings = vaultSettings;
		this.environment = environment;
		this.cleaner = cleaner;
	}

	@Override
	public boolean isApplicable(Volume caller) {
		if (this.environment.getMountPointsDir().isEmpty()) {
			LOG.warn("\"cryptomator.mountPointsDir\" is not set to a valid path!");
			return false;
		}
		return true;
	}

	@Override
	public Optional<Path> chooseMountPoint(Volume caller) {
		assert environment.getMountPointsDir().isPresent();
		//clean leftovers of not-regularly unmounted vaults
		//see https://github.com/cryptomator/cryptomator/issues/1013 and https://github.com/cryptomator/cryptomator/issues/1061
		cleaner.clearIrregularUnmountDebrisIfNeeded();
		return this.environment.getMountPointsDir().map(this::choose);
	}


	private Path choose(Path parent) {
		String basename = this.vaultSettings.mountName().get();
		//regular
		Path mountPoint = parent.resolve(basename);
		if (Files.notExists(mountPoint)) {
			return mountPoint;
		}
		//with id
		mountPoint = parent.resolve(basename + " (" + vaultSettings.getId() + ")");
		if (Files.notExists(mountPoint)) {
			return mountPoint;
		}
		//with id and count
		for (int i = 1; i < MAX_TMPMOUNTPOINT_CREATION_RETRIES; i++) {
			mountPoint = parent.resolve(basename + "_(" + vaultSettings.getId() + ")_" + i);
			if (Files.notExists(mountPoint)) {
				return mountPoint;
			}
		}
		LOG.error("Failed to find feasible mountpoint at {}{}{}_x. Giving up after {} attempts.", parent, File.separator, basename, MAX_TMPMOUNTPOINT_CREATION_RETRIES);
		return null;
	}

	@Override
	public boolean prepare(Volume caller, Path mountPoint) throws InvalidMountPointException {
		try {
			switch (caller.getMountPointRequirement()) {
				case PARENT_NO_MOUNT_POINT -> {
					Files.createDirectories(mountPoint.getParent());
					LOG.debug("Successfully created folder for mount point: {}", mountPoint);
					return false;
				}
				case EMPTY_MOUNT_POINT -> {
					Files.createDirectories(mountPoint);
					LOG.debug("Successfully created mount point: {}", mountPoint);
					return true;
				}
				case NONE -> {
					//Requirement "NONE" doesn't make any sense here.
					//No need to prepare/verify a Mountpoint without requiring one...
					throw new InvalidMountPointException(new IllegalStateException("Illegal MountPointRequirement"));
				}
				default -> {
					//Currently the case for "PARENT_OPT_MOUNT_POINT"
					throw new InvalidMountPointException(new IllegalStateException("Not implemented"));
				}
			}
		} catch (IOException exception) {
			throw new InvalidMountPointException("IOException while preparing mountpoint", exception);
		}
	}

	@Override
	public void cleanup(Volume caller, Path mountPoint) {
		try {
			Files.delete(mountPoint);
			LOG.debug("Successfully deleted mount point: {}", mountPoint);
		} catch (IOException e) {
			LOG.warn("Could not delete mount point: {}", e.getMessage());
		}
	}

}
