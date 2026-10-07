package editor;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Window-free testable contents of the editor binding preferences dialog. */
final class BindingPreferencesPanel extends JPanel {
    private record ActionChoice(String id,String label){@Override public String toString(){return label+"  ("+id+")";}}
    private final EditorInputBindings bindings;
    private final DefaultTableModel model=new DefaultTableModel(new Object[]{"Kind","Shortcut or gesture","Action"},0){
        @Override public boolean isCellEditable(int row,int column){return true;}
    };
    private final JTable table=new JTable(model);
    private final JLabel status=new JLabel(" ");
    private final JTextArea effective=new JTextArea(2,60);
    private final Map<String,ActionChoice> choices=new LinkedHashMap<>();

    BindingPreferencesPanel(EditorInputBindings bindings,Runnable close){
        super(new BorderLayout(8,8));this.bindings=Objects.requireNonNull(bindings);setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        EditorBindingProfile.KEY_ACTIONS.forEach((id,label)->choices.put(id,new ActionChoice(id,label)));
        EditorBindingProfile.MOUSE_ACTIONS.forEach((id,label)->choices.put(id,new ActionChoice(id,label)));
        table.setRowHeight(24);table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(new JComboBox<>(EditorBindingProfile.Kind.values())));
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<>(choices.values().toArray(ActionChoice[]::new))));
        table.getColumnModel().getColumn(0).setPreferredWidth(80);table.getColumnModel().getColumn(0).setMaxWidth(100);
        table.getColumnModel().getColumn(1).setPreferredWidth(285);table.getColumnModel().getColumn(2).setPreferredWidth(335);
        var title=new JLabel("Keyboard and mouse bindings");title.setFont(title.getFont().deriveFont(Font.BOLD,16f));
        var guidance=new JLabel("<html><div style='width:700px'>Examples: <b>ctrl+z</b>, <b>right+drag+viewport</b>, <b>key.space+middle+drag+viewport</b>, <b>key.q+right+drag+viewport</b>.<br>Left mouse is reserved for selection and handles. Space is captured only while a viewport has focus.</div></html>");
        var heading=new JPanel();heading.setLayout(new BoxLayout(heading,BoxLayout.Y_AXIS));heading.add(title);heading.add(Box.createVerticalStrut(5));heading.add(guidance);add(heading,BorderLayout.NORTH);
        var scroll=new JScrollPane(table);scroll.setColumnHeaderView(table.getTableHeader());add(scroll,BorderLayout.CENTER);
        var addKey=new JButton("Add keyboard");addKey.addActionListener(_->{model.addRow(new Object[]{EditorBindingProfile.Kind.KEY,"",choices.get("history.undo")});editLast();});
        var addMouse=new JButton("Add mouse");addMouse.addActionListener(_->{model.addRow(new Object[]{EditorBindingProfile.Kind.MOUSE,"",choices.get("view.orbit")});editLast();});
        var remove=new JButton("Remove selected");remove.addActionListener(_->{int[] rows=table.getSelectedRows();for(int i=rows.length-1;i>=0;i--)model.removeRow(rows[i]);});
        var restore=new JButton("Restore defaults");restore.addActionListener(_->setRows(bindings.defaults()));
        var apply=new JButton("Apply");apply.addActionListener(_->apply(false));var save=new JButton("Save");save.addActionListener(_->apply(true));
        var cancel=new JButton("Close / Cancel draft");cancel.addActionListener(_->close.run());
        var editButtons=new JPanel(new FlowLayout(FlowLayout.LEFT,6,2));editButtons.add(addKey);editButtons.add(addMouse);editButtons.add(remove);editButtons.add(restore);
        var commitButtons=new JPanel(new FlowLayout(FlowLayout.LEFT,6,2));commitButtons.add(apply);commitButtons.add(save);commitButtons.add(cancel);
        var buttonRows=new JPanel();buttonRows.setLayout(new BoxLayout(buttonRows,BoxLayout.Y_AXIS));buttonRows.add(editButtons);buttonRows.add(commitButtons);
        effective.setEditable(false);effective.setFocusable(false);effective.setOpaque(false);effective.setLineWrap(true);effective.setWrapStyleWord(true);
        status.setForeground(new Color(170,35,35));var footer=new JPanel(new BorderLayout());footer.add(buttonRows,BorderLayout.NORTH);footer.add(status,BorderLayout.CENTER);footer.add(effective,BorderLayout.SOUTH);add(footer,BorderLayout.SOUTH);
        setRows(bindings.entries());refreshEffective();setPreferredSize(new Dimension(760,430));
    }

    private void editLast(){int row=model.getRowCount()-1;table.changeSelection(row,1,false,false);table.editCellAt(row,1);table.requestFocusInWindow();}
    private void apply(boolean save){
        stopEditing();try{if(save)bindings.saveAndApply(draft());else bindings.apply(draft());status.setForeground(new Color(32,120,70));status.setText(save?"Saved and applied":"Applied for this editor session");refreshEffective();}
        catch(RuntimeException error){status.setForeground(new Color(170,35,35));status.setText(message(error));}
    }
    private List<EditorBindingProfile.Entry> draft(){
        var result=new ArrayList<EditorBindingProfile.Entry>();for(int row=0;row<model.getRowCount();row++){
            var kind=(EditorBindingProfile.Kind)model.getValueAt(row,0);var chord=Objects.toString(model.getValueAt(row,1),"");var actionValue=model.getValueAt(row,2);
            var action=actionValue instanceof ActionChoice choice?choice.id():Objects.toString(actionValue,"");result.add(new EditorBindingProfile.Entry(kind,chord,action));
        }return result;
    }
    private void setRows(List<EditorBindingProfile.Entry> rows){stopEditing();model.setRowCount(0);for(var row:rows)model.addRow(new Object[]{row.kind(),row.chord(),choices.getOrDefault(row.action(),new ActionChoice(row.action(),row.action()))});status.setText(" ");}
    private void refreshEffective(){effective.setText("Active navigation: "+bindings.navigationHelp());}
    private void stopEditing(){if(table.isEditing())table.getCellEditor().stopCellEditing();}
    private static String message(Throwable error){return error.getMessage()==null||error.getMessage().isBlank()?error.getClass().getSimpleName():error.getMessage();}

    void setRowsForTest(List<EditorBindingProfile.Entry> rows){setRows(rows);}
    void applyForTest(){apply(false);}
    void saveForTest(){apply(true);}
    String statusForTest(){return status.getText();}
    List<EditorBindingProfile.Entry> draftForTest(){return draft();}
}
