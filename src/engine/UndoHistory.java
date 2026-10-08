package engine;

import java.util.*;
import java.util.function.Consumer;

/** Optional bounded authoring history; games can edit SceneDocument directly at zero history cost. */
public final class UndoHistory {
    private record Entry(String label,SceneSnapshot content){}
    private final SceneDocument document;private final int limit;
    private final Thread owner=Thread.currentThread();
    private final ArrayDeque<Entry> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private long expectedRevision;
    private Entry group;
    public UndoHistory(SceneDocument document,int limit){this.document=Objects.requireNonNull(document);if(limit<0)throw new IllegalArgumentException("History limit cannot be negative");this.limit=limit;expectedRevision=document.snapshot().revision();}
    public SceneSnapshot edit(String label,Consumer<SceneEdit> operation){
        ready();var before=document.snapshot();var after=document.transact(operation);expectedRevision=after.revision();
        if(after!=before&&limit>0){undo.addLast(new Entry(label,before));while(undo.size()>limit)undo.removeFirst();redo.clear();}
        return after;
    }
    public SceneSnapshot replace(String label,SceneSnapshot content){ready();var before=document.snapshot();var after=document.replace(content);expectedRevision=after.revision();if(after!=before&&limit>0){undo.addLast(new Entry(label,before));while(undo.size()>limit)undo.removeFirst();redo.clear();}return after;}
    /** Start a live edit whose intermediate publications become one undo entry on commit. */
    public void beginGroup(String label){ready();group=new Entry(Objects.requireNonNull(label),document.snapshot());}
    public SceneSnapshot updateGroup(Consumer<SceneEdit> operation){owner();sync();if(group==null)throw new IllegalStateException("No grouped edit is active");var after=document.transact(operation);expectedRevision=after.revision();return after;}
    public SceneSnapshot commitGroup(){owner();sync();if(group==null)throw new IllegalStateException("No grouped edit is active");var entry=group;group=null;var current=document.snapshot();if(!current.sameContent(entry.content())&&limit>0){undo.addLast(entry);while(undo.size()>limit)undo.removeFirst();redo.clear();}return current;}
    public SceneSnapshot cancelGroup(){owner();sync();if(group==null)throw new IllegalStateException("No grouped edit is active");var entry=group;var current=document.snapshot();if(!current.sameContent(entry.content()))current=document.replace(entry.content());expectedRevision=current.revision();group=null;return current;}
    public boolean groupActive(){owner();sync();return group!=null;}
    public boolean canUndo(){owner();sync();return group==null&&!undo.isEmpty();}
    public boolean canRedo(){owner();sync();return group==null&&!redo.isEmpty();}
    public SceneSnapshot undo(){ready();if(undo.isEmpty())throw new IllegalStateException("Nothing to undo");var current=document.snapshot();var entry=undo.peekLast();var value=document.replace(entry.content());undo.removeLast();if(limit>0)redo.addLast(new Entry(entry.label(),current));expectedRevision=value.revision();return value;}
    public SceneSnapshot redo(){ready();if(redo.isEmpty())throw new IllegalStateException("Nothing to redo");var current=document.snapshot();var entry=redo.peekLast();var value=document.replace(entry.content());redo.removeLast();if(limit>0){undo.addLast(new Entry(entry.label(),current));while(undo.size()>limit)undo.removeFirst();}expectedRevision=value.revision();return value;}
    public void clear(){ready();undo.clear();redo.clear();expectedRevision=document.snapshot().revision();}
    public int undoSize(){owner();sync();return undo.size();}
    public int redoSize(){owner();sync();return redo.size();}
    private void ready(){owner();sync();if(group!=null)throw new IllegalStateException("A grouped edit is active");}
    private void sync(){long actual=document.snapshot().revision();if(actual!=expectedRevision){undo.clear();redo.clear();group=null;expectedRevision=actual;}}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Undo history belongs to its creating thread");}
}
