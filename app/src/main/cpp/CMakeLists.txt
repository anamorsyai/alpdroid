cmake_minimum_required(VERSION 3.22.1)
project(alpineterm_native C)

# A real executable, not a shared library — packaged into jniLibs under a lib*.so name purely
# to ride Android's install-time extraction/exec-permission mechanism for that directory (see
# app/build.gradle.kts). CMake still needs an add_executable target for AGP's external native
# build to produce it; the rename to lib*.so happens as a Gradle copy step afterward.
add_executable(pty_bridge pty_bridge.c)
target_compile_options(pty_bridge PRIVATE -O2 -Wall)

# Name the build output libpty_bridge.so (still a plain ELF executable, not a shared object) so
# AGP's external-native-build step — which only recognizes "lib*.so" outputs, not raw
# executables — picks it up and merges it into jniLibs on its own, same as any real .so target.
set_target_properties(pty_bridge PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    OUTPUT_NAME "pty_bridge"
)
