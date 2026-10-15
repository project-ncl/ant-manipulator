package org.jboss.pnc.antmanipulator.cli;

import org.jboss.pnc.mavenmanipulator.common.util.ManifestUtils;

import picocli.CommandLine;

public class ManifestVersionProvider
        implements CommandLine.IVersionProvider {
    /**
     * Returns version information for a command.
     *
     * @return version information (each string in the array is displayed on a separate line)
     */
    @Override
    public String[] getVersion() {
        return new String[] { "Ant CLI " + ManifestUtils.getManifestInformation(ManifestVersionProvider.class) };
    }
}
