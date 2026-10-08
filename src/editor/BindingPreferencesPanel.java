package editor;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Window-free testable, action-first contents of the editor binding preferences dialog. */
final class BindingPreferencesPanel extends JPanel {
    private record ActionDefinition(String group,String id,String label,EditorBindingProfile.Kind kind){}
    private static final List<ActionDefinition> ACTIONS=List.of(
            new ActionDefinition("History","history.undo","Undo",EditorBindingProfile.Kind.KEY),
            new ActionDefinition("History","history.redo","Redo",EditorBindingProfile.Kind.KEY),
            new ActionDefinition("History","gesture.cancel","Cancel active handle drag",EditorBindingProfile.Kind.KEY),
            new ActionDefinition("Navigation","view.orbit","Orbit view",EditorBindingProfile.Kind.MOUSE),
            new ActionDefinition("Navigation","view.pan","Pan view",EditorBindingProfile.Kind.MOUSE),
            new ActionDefinition("Navigation","view.zoom","Zoom view",EditorBindingProfile.Kind.MOUSE));

    private final class ActionCard extends JPanel {
        private final ActionDefinition definition;
        private final JPanel rows=new JPanel();
        private final List<JTextField> fields=new ArrayList<>();
        ActionCard(ActionDefinition definition){
            super(new BorderLayout(8,3));this.definition=definition;
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(210,214,220)),BorderFactory.createEmptyBorder(4,6,5,6)));
            var kind=definition.kind()==EditorBindingProfile.Kind.KEY?"Keyboard":"Mouse";
            var label=new JLabel("<html><b>"+definition.label()+"</b> &nbsp; <font color='#667080'>"+kind+"</font></html>");label.setToolTipText(definition.id());add(label,BorderLayout.NORTH);
            rows.setLayout(new BoxLayout(rows,BoxLayout.Y_AXIS));add(rows,BorderLayout.CENTER);
            var add=new JButton("Add binding");add.setToolTipText("Add an equivalent binding for "+definition.label());add.addActionListener(_->addField("",true));
            var controls=new JPanel(new FlowLayout(FlowLayout.LEFT,0,1));controls.add(add);add(controls,BorderLayout.SOUTH);
        }
        void setBindings(Collection<String> chords){fields.clear();rows.removeAll();for(var chord:chords)addField(chord,false);refreshRows();}
        void addField(String chord,boolean focus){
            var field=new JTextField(chord,34);field.setToolTipText(definition.kind()==EditorBindingProfile.Kind.KEY?"Keyboard shortcut, for example ctrl+z":"Mouse gesture, for example key.space+right+drag+viewport");fields.add(field);
            var row=new JPanel(new BorderLayout(5,0));row.add(field);var remove=new JButton("Remove binding");remove.setToolTipText("Remove this binding from "+definition.label());remove.addActionListener(_->{fields.remove(field);rows.remove(row);refreshRows();});row.add(remove,BorderLayout.EAST);rows.add(row);
            refreshRows();if(focus)SwingUtilities.invokeLater(field::requestFocusInWindow);
        }
        void refreshRows(){
            for(var component:rows.getComponents())if(component instanceof JLabel)rows.remove(component);
            if(fields.isEmpty()){var unbound=new JLabel("Unbound");unbound.setForeground(new Color(110,116,124));unbound.setFont(unbound.getFont().deriveFont(Font.ITALIC));rows.add(unbound);}
            rows.revalidate();rows.repaint();
        }
        List<String> chords(){return fields.stream().map(field->field.getText().trim()).toList();}
    }

    private static final class ActionListPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect,int orientation,int direction){return 18;}
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect,int orientation,int direction){return Math.max(18,visibleRect.height-18);}
        @Override public boolean getScrollableTracksViewportWidth(){return true;}
        @Override public boolean getScrollableTracksViewportHeight(){return false;}
    }

    private final EditorInputBindings bindings;
    private final Map<String,ActionCard> cards=new LinkedHashMap<>();
    private final JLabel status=new JLabel(" ");
    private final JTextArea effective=new JTextArea(2,60);

    BindingPreferencesPanel(EditorInputBindings bindings,Runnable close){
        super(new BorderLayout(8,8));this.bindings=Objects.requireNonNull(bindings);setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        var title=new JLabel("Keyboard and mouse bindings");title.setFont(title.getFont().deriveFont(Font.BOLD,16f));
        var guidance=new JLabel("<html><div style='width:700px'>Actions are fixed; every binding listed under an action is an equivalent alternative.<br>Examples: <b>ctrl+z</b>, <b>right+drag+viewport</b>, <b>key.space+middle+drag+viewport</b>.<br>Left mouse is reserved for selection and handles. Space is captured only while a viewport has focus.</div></html>");
        var heading=new JPanel();heading.setLayout(new BoxLayout(heading,BoxLayout.Y_AXIS));heading.add(title);heading.add(Box.createVerticalStrut(5));heading.add(guidance);add(heading,BorderLayout.NORTH);

        var contents=new ActionListPanel();contents.setLayout(new BoxLayout(contents,BoxLayout.Y_AXIS));
        for(var group:List.of("History","Navigation")){
            var groupPanel=new JPanel();groupPanel.setLayout(new BoxLayout(groupPanel,BoxLayout.Y_AXIS));groupPanel.setBorder(BorderFactory.createTitledBorder(group));
            for(var definition:ACTIONS)if(definition.group().equals(group)){var card=new ActionCard(definition);cards.put(definition.id(),card);groupPanel.add(card);}
            contents.add(groupPanel);contents.add(Box.createVerticalStrut(6));
        }
        var scroll=new JScrollPane(contents);scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);scroll.getVerticalScrollBar().setUnitIncrement(18);add(scroll,BorderLayout.CENTER);

        var restore=new JButton("Restore defaults");restore.addActionListener(_->setRows(bindings.defaults()));
        var apply=new JButton("Apply");apply.addActionListener(_->apply(false));var save=new JButton("Save");save.addActionListener(_->apply(true));
        var cancel=new JButton("Close / Cancel draft");cancel.addActionListener(_->close.run());
        var buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,6,2));buttons.add(restore);buttons.add(apply);buttons.add(save);buttons.add(cancel);
        effective.setEditable(false);effective.setFocusable(false);effective.setOpaque(false);effective.setLineWrap(true);effective.setWrapStyleWord(true);
        status.setForeground(new Color(170,35,35));var footer=new JPanel(new BorderLayout());footer.add(buttons,BorderLayout.NORTH);footer.add(status,BorderLayout.CENTER);footer.add(effective,BorderLayout.SOUTH);add(footer,BorderLayout.SOUTH);
        setRows(bindings.entries());refreshEffective();setPreferredSize(new Dimension(780,570));
    }

    private void apply(boolean save){
        try{if(save)bindings.saveAndApply(draft());else bindings.apply(draft());status.setForeground(new Color(32,120,70));status.setText(save?"Saved and applied":"Applied for this editor session");refreshEffective();}
        catch(RuntimeException error){status.setForeground(new Color(170,35,35));status.setText(message(error));}
    }
    private List<EditorBindingProfile.Entry> draft(){
        var result=new ArrayList<EditorBindingProfile.Entry>();for(var definition:ACTIONS)for(var chord:cards.get(definition.id()).chords())result.add(new EditorBindingProfile.Entry(definition.kind(),chord,definition.id()));return result;
    }
    private void setRows(List<EditorBindingProfile.Entry> entries){
        var byAction=new LinkedHashMap<String,List<String>>();for(var definition:ACTIONS)byAction.put(definition.id(),new ArrayList<>());
        for(var entry:entries){var values=byAction.get(entry.action());if(values==null)throw new IllegalArgumentException("Unknown editor action: "+entry.action());values.add(entry.chord());}
        for(var definition:ACTIONS)cards.get(definition.id()).setBindings(byAction.get(definition.id()));status.setText(" ");
    }
    private void refreshEffective(){effective.setText("Active navigation: "+bindings.navigationHelp());}
    private static String message(Throwable error){return error.getMessage()==null||error.getMessage().isBlank()?error.getClass().getSimpleName():error.getMessage();}

    void setRowsForTest(List<EditorBindingProfile.Entry> rows){setRows(rows);}
    void setBindingsForTest(String action,List<String> chords){var card=cards.get(action);if(card==null)throw new IllegalArgumentException("Unknown editor action: "+action);card.setBindings(chords);}
    void addBindingForTest(String action,String chord){var card=cards.get(action);if(card==null)throw new IllegalArgumentException("Unknown editor action: "+action);card.addField(chord,false);}
    void restoreDefaultsForTest(){setRows(bindings.defaults());}
    List<String> actionIdsForTest(){return List.copyOf(cards.keySet());}
    List<String> bindingsForTest(String action){return cards.get(action).chords();}
    void applyForTest(){apply(false);}
    void saveForTest(){apply(true);}
    String statusForTest(){return status.getText();}
    List<EditorBindingProfile.Entry> draftForTest(){return draft();}
}
