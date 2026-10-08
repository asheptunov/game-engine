package engine.input;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

/** Strict properties-file loading plus deterministic save for binding tables. */
public final class BindingFiles {
    private BindingFiles(){}
    public static <B extends BindingTable>B load(Path path,B target){
        try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){return load(reader,target);}
        catch(NoSuchFileException error){throw new IllegalStateException("Bindings file not found: "+path.toAbsolutePath(),error);}
        catch(IOException error){throw new IllegalStateException("Failed to read bindings file: "+path.toAbsolutePath(),error);}
    }
    public static <B extends BindingTable>B load(Reader reader,B target){
        var properties=new Properties();
        try{properties.load(reader);}catch(IOException error){throw new IllegalStateException("Failed to parse bindings",error);}
        for(var chord:properties.stringPropertyNames().stream().sorted().toList()){
            var action=properties.getProperty(chord).trim();
            if(action.isEmpty())throw new IllegalStateException("Empty action id for chord '"+chord+"'");
            target.bindParsed(chord,action);
        }
        return target;
    }
    public static void save(Path path,BindingTable source){
        try{var parent=path.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);
            try(var writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8)){save(writer,source);}}
        catch(IOException error){throw new IllegalStateException("Failed to write bindings file: "+path.toAbsolutePath(),error);}
    }
    public static void save(Writer writer,BindingTable source){
        try{
            for(var entry:source.serialized().entrySet().stream().sorted(Comparator.comparing(java.util.Map.Entry::getKey)).toList()){
                writer.write(escape(entry.getKey(),true));writer.write(" = ");writer.write(escape(entry.getValue(),false));writer.write('\n');
            }
            writer.flush();
        }catch(IOException error){throw new IllegalStateException("Failed to write bindings",error);}
    }
    private static String escape(String value,boolean key){
        var out=new StringBuilder();
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c=='\n'){out.append("\\n");continue;}if(c=='\r'){out.append("\\r");continue;}if(c=='\t'){out.append("\\t");continue;}
            if(c=='\\' || key&&(c=='='||c==':'||c=='#'||c=='!') || i==0&&Character.isWhitespace(c))out.append('\\');
            out.append(c);
        }
        return out.toString();
    }
}
