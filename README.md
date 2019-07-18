# NativeUtils

User-friendly Java & C# library for deploying&loading native libraries and accompanying resources from JAR archive/classpath/.NET assembly to local filesystem.

## Features
 * Accepts resource path template describing what set of resources to deploy depending on the operating system(Windows/Linux/OSX) and CPU pointer size (32/64).
 * Supports decompression of files compressed with ZStandard archiver.
 * Will load deployed dynamic libraries, so they can be used via JNI / PInvoke.
 * Can try to deploy to several possible deployment paths and can handle conflicts between multiple instances simultaneously trying to deploy to the same location.
 * Supports concurrent deployment and shared use of the same native library by Java and C# programs
 * Supports specifying dynamic library loading order
 * Can overwrite or reuse existing deployed files
 * Can deploy to a random temporary subdirectory, cleaned on exit or on the next run
 * Tries to protect loaded DLs from accidental deletion or corruption by other instances of the user's application.

## Acknowledgements
 Originally based on native-utils project:
 * <http://adamheinrich.com/blog/2012/how-to-load-native-jni-library-from-jar>
 * <https://github.com/adamheinrich/native-utils>


## License
This library is released under Apache 2.0 license. See ([license.txt](license.txt))


## Requirements/dependencies
Uses Deltix ZStandard decompressor <https://github.com/deltixlab/Zstandard.git>

### Java
Requires a Java 1.8+ virtual machine containing the `sun.misc.Unsafe` interface running on a little endian platform.

Uses Gradle build tool.
### C#
Requires .NET platform that supports netstandard1.3 or .NET Core 1.0 or .NET Framework 4.0

Uses Cake build tool, which may also require Mono on Linux systems.

## Usage
Basic usage case is deploying and loading any files from a resource path specified by template to a relative or absolute filesystem path. All deployed dynamic libraries are loaded with `System.load()` (Java) or `dlopen()`/`LoadLibrary()` (C#)

Optional configuration flags may be used to specify how the files are written and loaded.
### Java

#### Java: Adding resources to your project
This is an example of how you can add the resource files via Gradle build script:
```
sourceSets {
    main {
        java {
            resources {
                srcDirs = ["$rootDir/native/bin/Release/"]
                include '**/*_so.zst'
                include '**/*.so.zst'
                include '**/*.dylib.zst'
                include '**/*.dll.zst'
            }
        }
    }
}
```
#### Java: Adding the library to your project
This is an example of how to add the library as a dependency in Gradle
```
dependencies {
    implementation group: 'rtmath', name: 'rtmath-native-utils', version: '1.9.0'
}
```

#### Java: Invocation
```.java
ResourceLoader.from("resources/$(OS)/$(ARCH)/*").to("some/path/$(ARCH)").load();
```

### C#

#### .NET: Adding resources to your project
Lets assume you have 4 versions of a native library for 2 platforms(Windows/Linux) and 2 architectures (32/64) and want to unpack/load it from your C# Library Assembly.
Your files are compressed with ZStandard compressor and added to the project under the following names:
```
<ItemGroup>
        <EmbeddedResource Include="native\win\32\lib.dll.zst" Link="Windows\x32\lib_dll_zst" />
        <EmbeddedResource Include="native\win\64\lib.dll.zst" Link="Windows\x64\lib_dll_zst" />
        <EmbeddedResource Include="native\linux\32\lib.so.zst" Link="Linux\x32\lib_so_zst" />
        <EmbeddedResource Include="native\linux\64\lib.so.zst" Link="Linux\x64\lib_so_zst" />
</ItemGroup>
```
Underscores are used here to avoid a bug with MS build tools. They will be automatically changed back to `.` so the file again has proper extension.

#### .NET: Adding the library to your project
`ResourceLoader` is usually added to your project in the source form, possibly as Git submodule. Currently it consists of 4 files, not including ZStandard decompressor (whose source files also need to be added)
```
    <Compile Include="..\NativeUtils\FileStream.cs" Link="NativeUtils\FileStream.cs" />
    <Compile Include="..\NativeUtils\ResourceLoader.cs" Link="NativeUtils\ResourceLoader.cs" />
    <Compile Include="..\NativeUtils\ResourceLoaderUtils.cs" Link="NativeUtils\ResourceLoaderUtils.cs" />
    <Compile Include="..\NativeUtils\FileJanitor.cs" Link="NativeUtils\FileJanitor.cs" />
```

#### .NET: Invocation
In order to unpack and load the version corresponding to your current platform and architecture you need to call the following code in the static constructor of your class(may not work on Mono, see below):
```
ResourceLoader.From("MyLibraryName.$(OS).x$(ARCH).*").To("SomeDirectoryName/$(VERSION)/$(ARCH)").Load();
```
If your system is 64-bit Windows, this will unpack the native library as: `%ProgramData%\SomeDirectoryName\Windows\64\lib.dll` and load it into memory. You just need to reference "lib" in DllImport, like this:
```
[DllImport("lib")]
extern static void MyFunction(int x);
```
The file will not be deleted when the program is finished and will be reused when the program is invoked again. Use some versioning system in order to verify that the correct version of the executable is loaded. Or use `AlwaysOverwrite`, or specify deployment path with a random component.
```.csharp
ResourceLoader.From("MyApp.$(OS).$(ARCH).*").To("some/path/$(ARCH)").Load();
```

#### .NET: Usage on Mono
Due to the peculiarities of JIT compiler in Mono runtime, you should put your native methods in a non-static wrapper class. You should call `ResourceLoader` to load the library from static constructor of the wrapper class, and later use native methods through the instance of the wrapper class. This has negligible(but nonzero) performance cost of 1-3 CPU cycles. You should not reference your native methods before creating an instance of the wrapper class and storing it somewhere, anywhere in the call tree.

See the sample code.


### Deployment path selection
Deployment path template is defined by `.To` clause and also affected by some configuration flags.
Forward slash symbol (`/`) should be used to specify path components in a platform-independent way.


#### Relative and absolute deployment paths
`ResourceLoader` handles absolute and relative deployment paths differently.
Absolute path is the path for which `Path.IsRooted(path)` returns `true` (after template substitution). If you want specify an absolute path, you are responsible for it being valid for the current platform.

Relative path (the library will choose the base path):
```
.To("DirName/SubdirName/$(VERSION)/$(ARCH)/AnotherSubDirName")
```
Absolute path:
```
.To("/var/tmp/CompanyName/$(ARCH)")
```
or
```
.To("$(TEMP)/CompanyName/$(ARCH)")
```

#### Relative deployment paths
If a relative deployment path is specified, several root paths may be tried, until deployment succeeds. The paths are platform-dependent.
* Windows: `ProgramData/userPath`, `AppData/userPath`, `TEMP/userPath`, `TEMP/userPath/$(RANDOM)`
* Mac OS: `~/Library/Application Support/userPath`, `TEMP/userPath`, `TEMP/userPath/$(RANDOM)`
* Linux: `~/.local/share/userPath`, `TEMP/userPath`, `TEMP/userPath/$(RANDOM)`


#### Absolute deployment paths
If absolute path is specified, it is used as-is. Optionally, `$(RANDOM)` subdirectory may be also tried, if `TryRandomFallbackSubDirectory` is set to `true`

#### Cleanup
By default, the application will try to clean subdirectories within deployment directory if the files they contain are not locked. This takes care of the garbage created by using random subdirectories.

### Template substitution
Arguments of `.From()` & `.To()` calls are treated as templates, variable substitution is performed.
Template variables are specified as `$(VARIABLE_NAME)` and substituted with the value calculated at runtime.
They are useful for specifying platform-dependent paths in a language/platform independent way without requiring user to write platform detection code.
Syntax validation will throw exception if the template string is malformed.

#### Template variables
Valid for source and destination arguments:
* `OS` -> `Windows`/`Linux`/`OSX` (OS platform name)
* `ARCH` -> `32`/`64` (platform pointer size, probably will be later replaced with `ARCH` and `ARCH_BITS` variables)
* `DLLEXT` -> `dll`/`so`/`dylib` (Dynamic Library extension for the current operating system)
* `VERSION` -> Assembly version property (such as `1.2.3.4`) for .NET, `class.getPackage().getImplementationVersion()` for Java

Valid for destination argument only:
* `RANDOM` -> random directory name, currently 8 hexadecimal digits
* `TEMP` -> absolute path of the system temporary directory.

### Filename transformation

Source filenames are transformed before attempting to create or verify the corresponding files in the destination directory.

* All `_` characters are replaced with `.`.
* `.zst` extension is removed if found at the end of the filename.
* Tags (see below) are also removed.
* Dynamic libraries are identified by checking file extension (platform dependent)
* Optional suffix is appended to dynamic library filenames before the extension.


### Tags
Source filenames may contain tags. Tag is a key/value pair: `[key@value]`
Every tag found in a filename is replaced with empty string after parsing.

The only tag currently supported is `order`, whose value is a non-negative integer, specifying dynamic library loading order. Libraries, whose order is not specified are loaded _after_ libraries, whose order is specified explicitly.


### ZStandard support

There is support for ZStandard file compression format.

If a resource file name ends with `.zst`/`_zst` the resource will be decompressed and the suffix will be removed.

### Dynamic libraries

Files, whose names end with `dll`/`so`/`dylib` extension(depending on the platform), will be loaded into memory. `System.load()` is used for Java, `LoadLibrary`/`dlopen` for .NET. They will be loaded in the order they are found, unless overridden with `order` tag.

