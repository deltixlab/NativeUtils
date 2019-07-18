#tool nuget:?package=NUnit.ConsoleRunner&version=3.7.0
#addin "Cake.FileHelpers"
#addin "Cake.Incubator"

//////////////////////////////////////////////////////////////////////
// ARGUMENTS
//////////////////////////////////////////////////////////////////////

var target = Argument("target", "Default");
var configuration = Argument("configuration", "Release");

//////////////////////////////////////////////////////////////////////
// PREPARATION
//////////////////////////////////////////////////////////////////////

// Define directories.
var csDir = ".";
var gradleRootDir = "..";
var nativeProjectDir = "../native-lib";

var nativeProjectName = "Sample";
var mainLibProjectName = $"SampleDll";
var testProjectName = $"SampleTests";

var nativeBinDir = $"{nativeProjectDir}/bin";
var slnPath = $"{csDir}/Samples.sln";

// Parse version from gradle.properties
var gradleProperties = new Dictionary<String, String>();
foreach (var row in System.IO.File.ReadAllLines($"{gradleRootDir}/gradle.properties"))
    gradleProperties.Add(row.Split('=')[0], String.Join("=",row.Split('=').Skip(1).ToArray()));

var version = gradleProperties["version"];
var index = version.IndexOf("-");
var dotNetVersion = (index > 0 ? version.Substring(0, index) : version) + ".0";

//////////////////////////////////////////////////////////////////////
// Helpers
//////////////////////////////////////////////////////////////////////

String prjDir(String name) { return $"{csDir}/{name}"; }
String prjPath(String name) { return $"{prjDir(name)}/{name}.csproj"; }
String binDir(String name) { return $"{prjDir(name)}/bin/{configuration}"; }
String objDir(String name) { return $"{prjDir(name)}/obj/{configuration}"; }

void echo(string s) { Console.WriteLine(s); }

// rm -rf <dir>
void DeleteDir(string dir)
{
    if (DirectoryExists(dir))
        DeleteDirectory(new DirectoryPath(dir), new DeleteDirectorySettings { Recursive = true, Force = true });
}

//////////////////////////////////////////////////////////////////////
// TASKS
//////////////////////////////////////////////////////////////////////

Task("Clean")
    .Does(() =>
{
    DotNetCoreClean(slnPath,
        new DotNetCoreCleanSettings { Configuration = configuration }
);
});

Task("Restore-NuGet-Packages")
    .IsDependentOn("Clean")
    .Does(() =>
{
    DotNetCoreRestore(slnPath);
});


void BuildNativeTarget(int arch, bool isWindows)
{
    arch = 32 == arch && isWindows ? 86 : arch;
    string targetName = $"{nativeProjectName}";
    StartProcess(isWindows ? "MSBuild" : "make",
        new ProcessSettings { Arguments =
            isWindows ? $"/p:TargetName={targetName} /p:Platform=x{arch} /p:Configuration={configuration} /t:Rebuild /m:4 {nativeProjectName}.sln"
            : $"ProjectName={targetName} Architecture={arch} Configuration={configuration} Build",
        WorkingDirectory = nativeProjectDir });
 }

void BuildNativeLib(bool isWindows)
{
    DeleteDir($"{nativeProjectDir}/obj");
    foreach (var arch in new int[]{32, 64})
        BuildNativeTarget(arch, isWindows);
}

Task("BuildNativeLinux")
    .Does(() =>
{
    BuildNativeLib(false);
});

Task("BuildNativeWindows")
    .Does(() =>
{
    BuildNativeLib(true);
});

Task("GenerateDummyFiles")
    .Does(() =>
{
    string path = $"{nativeBinDir}/dummy";
    DeleteDir(path);
    CreateDirectory(path);
    int[] sizes = {1000, 100000, 1000000, 10000000 };
    for (int i = 1; i <= 4; ++i)
        for (int j = 0; j < 4; ++j)
            FileWriteText($"{path}/dummy{i}{j}.txt", new string((char)('0' + i), sizes[i-1]));
});

Task("CompressDummyFiles")
    .IsDependentOn("GenerateDummyFiles")
    .Does(() =>
{
    StartProcess("zstd", $"-19 --rm -r {nativeBinDir}/dummy");
});

Task("CompressNative")
    .IsDependentOn("GenerateDummyFiles")
    .IsDependentOn("CompressDummyFiles")
    .Does(() =>
{
    var path = $"{nativeBinDir}/Release";
	DeleteFiles($"{path}/**/*.zst");
	StartProcess("zstd", $"-19 -r {path}");
    // Dotnet resources compilation workaround

    foreach (var arch in new int[]{32, 64})
        if (DirectoryExists($"{path}/Linux/{arch}"))
            MoveFile($"{path}/Linux/{arch}/lib{nativeProjectName}.so.zst",
                $"{path}/Linux/{arch}/lib{nativeProjectName}_so.zst");
});

void BuildDll()
{
    var buildSettings = new DotNetCoreBuildSettings {
        Configuration = configuration,
        NoRestore = true,
		MSBuildSettings = new DotNetCoreMSBuildSettings()
    };

    if (!IsRunningOnWindows())
        buildSettings.Framework = "netstandard2.0";

    DotNetCoreBuild(prjPath(mainLibProjectName), buildSettings);
}

void BuildTests()
{
    var buildSettings = new DotNetCoreBuildSettings
    {
        Configuration = configuration,
        NoRestore = true,
        NoDependencies = true
    };

    if (!IsRunningOnWindows())
        buildSettings.Framework = "netcoreapp2.0";

	DotNetCoreBuild(prjPath("Sample1"), buildSettings);
	DotNetCoreBuild(prjPath("Sample2"), buildSettings);
	//DotNetCoreBuild(prjPath(testProjectName), buildSettings);
}

Task("Build")
    .IsDependentOn("Restore-NuGet-Packages")
    .Does(() =>
{
    BuildDll();
    BuildTests();
});

Task("Run-Unit-Tests")
    .IsDependentOn("Build")
    .Does(() =>
{
	var settings = new DotNetCoreTestSettings()
	{
        Configuration = configuration
        //, DiagnosticOutput = true
        //, DiagnosticFile = "stdout.txt"
	};

    if (!IsRunningOnWindows())
        settings.Framework = "netcoreapp2.0";

    var testProjectName = "Sample2";

    Information("Running test project ''" + testProjectName + "' with .NET Core");
    DotNetCoreTest(prjPath(testProjectName), settings);

	// Prevent from running on platforms without .NET 4.0

	var glob = $"{binDir(testProjectName)}/net40/{testProjectName}.exe";
    if (IsRunningOnWindows() && GetFiles(glob).Count > 0)
    {
        Information("Running test project ''" + testProjectName + "' with NUnit & .NET Framework 4.0");
        NUnit3(glob);
    }
});

//////////////////////////////////////////////////////////////////////
// TASK TARGETS
//////////////////////////////////////////////////////////////////////

Task("Default")
    .IsDependentOn("Run-Unit-Tests");

//////////////////////////////////////////////////////////////////////
// EXECUTION
//////////////////////////////////////////////////////////////////////

RunTarget(target);
