This directory contains the source code of TeamCity VCS plugin for ClearCase integration.

The source code is licensed under Apache 2.0 license ( http://www.apache.org/licenses/LICENSE-2.0 )

NOTE: This source code is compatibe with TeamCity 4.0 EAP builds.

build.xml         - Ant (1.6.5+) build script file.
build.properties  - Property file to store location of TeamCity distribution

To build the plugin download TeamCity .tar.gz distribution, unpack it and modify build.properties file to store the location.
Run "ant -f build.xml" to build and package the plugin into "dist\clearcase.zip".

More information on TeamCity: http://confluence.jetbrains.com/display/TW/