package deltix;

import rtmath.utilities.*;

import static deltix.NativeUtilsSample.Imports.avg;

// Test program for concurrency tests
public class NativeUtilsSample {
    public static class Imports {
        static {
            ResourceLoader
                .from("$(OS)/$(ARCH)/*")
                .to("$(TEMP)/NativeUtilsSample/$(ARCH)")
                .alwaysOverwrite(true)
                .load();
        }

        native static double avg(int a, int b);
    }

    public static void main(String[] args)  {

        try {
            System.out.println("ResourceLoader sample");
            System.out.printf("avg(2,7) = %s%n", avg(2, 7));
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
