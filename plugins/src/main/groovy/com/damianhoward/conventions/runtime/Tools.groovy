package com.damianhoward.conventions.runtime

/** The path of a JDK tool, which carries an .exe suffix on the one platform the workstation runs. */
final class Tools {
    private Tools() {}

    static String of(File jdkHome, String tool) {
        String name = System.getProperty('os.name').startsWith('Windows') ? "${tool}.exe" : tool
        new File(jdkHome, "bin/${name}").path
    }
}
