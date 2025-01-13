package rendering;

public interface Printer {
    void print(char c, int x, int y, int size);

    void print(String str, int x, int y, int size);
}
