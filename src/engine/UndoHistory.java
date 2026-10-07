package engine;

import java.util.*;
import java.util.function.Consumer;

/** Optional bounded authoring history; games can edit SceneDocument directly at zero history cost. */
public final class UndoHistory {
    private record Entry(String label,SceneSnapshot content){}
    private final SceneDocument document;private final int limit;
    private final ArrayDeque<Entry> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private long expectedRevision;
    public UndoHistory(SceneDocument document,int limit){this.document=Objects.requireNonNull(document);if(limit<0)throw new IllegalArgumentException("History limit cannot be negative");this.limit=limit;expectedRevision=document.snapshot().revision();}
    public SceneSnapshot edit(String label,Consumer<SceneEdit> operation){
        sync();var before=document.snapshot();var after=document.transact(operation);expectedRevision=after.revision();
        if(after!=before&&limit>0){undo.addLast(new Entry(label,before));while(undo.size()>limit)undo.removeFirst();redo.clear();}
        return after;
    }
    public SceneSnapshot replace(String label,SceneSnapshot content){sync();var before=document.snapshot();var after=document.replace(content);expectedRevision=after.revision();if(after!=before&&limit>0){undo.addLast(new Entry(label,before));while(undo.size()>limit)undo.removeFirst();redo.clear();}return after;}
    public boolean canUndo(){sync();return !undo.isEmpty();}
    public boolean canRedo(){sync();return !redo.isEmpty();}
    public SceneSnapshot undo(){sync();if(undo.isEmpty())throw new IllegalStateException("Nothing to undo");var current=document.snapshot();var entry=undo.peekLast();var value=document.replace(entry.content());undo.removeLast();if(limit>0)redo.addLast(new Entry(entry.label(),current));expectedRevision=value.revision();return value;}
    public SceneSnapshot redo(){sync();if(redo.isEmpty())throw new IllegalStateException("Nothing to redo");var current=document.snapshot();var entry=redo.peekLast();var value=document.replace(entry.content());redo.removeLast();if(limit>0){undo.addLast(new Entry(entry.label(),current));while(undo.size()>limit)undo.removeFirst();}expectedRevision=value.revision();return value;}
    public void clear(){undo.clear();redo.clear();expectedRevision=document.snapshot().revision();}
    public int undoSize(){sync();return undo.size();}
    public int redoSize(){sync();return redo.size();}
    private void sync(){long actual=document.snapshot().revision();if(actual!=expectedRevision){undo.clear();redo.clear();expectedRevision=actual;}}
}
