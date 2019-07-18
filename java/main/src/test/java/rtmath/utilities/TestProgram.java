package rtmath.utilities;

// Test program for concurrency tests
public class TestProgram {
    public static void main(String[] args)  {

        System.out.println("ResourceLoader test started, args: ");
        for (String s : args) {
            System.out.print(s);
            System.out.print(' ');
        }

        System.out.println();
        if (args.length < 2)
            throw new IllegalArgumentException("args.length must be >= 2");

        ResourceLoaderDone rl = null;
        try {
            rl = ResourceLoader
                .from(args[0])
                .to(args[1])
                .load();

            String path = rl.getActualDeploymentPath();
            System.out.print("OK!: " + path);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
