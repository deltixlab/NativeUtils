package rtmath.utilities;

/**
 * ResourceLoader instance without the builder interface.
 */
public interface ResourceLoaderDone extends ResourceLoaderInstance.ResourceLoaderBase {

    /**
     * Unload (decrement reference count) all loaded Dynamic Libraries.
     * Does not work in Java version of ResourceLoader, but remains for source code compatibility.
     * Does not unlock the files (will only be unlocked on exit).
     * @return This ResourceLoader instance ({@code ResourceLoaderDone})
     */
    ResourceLoaderDone unloadDlls();
}
