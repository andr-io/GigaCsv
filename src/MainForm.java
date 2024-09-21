import com.andreyprodromov.csv.CsvMagikk;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.filechooser.FileFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;
import java.util.function.Supplier;

public class MainForm extends JFrame {

    private static final int CACHE_INVALID = -1;
    private static final int MOUSE_LEFT_CLICK = 1;
    private static final int MOUSE_RIGHT_CLICK = 3;

    private static final CsvMagikk PARSER = new CsvMagikk();

    private JTable mainTable;
    private JButton searchButton;
    private JButton filterButton;
    private JTextField cellTextField;
    private JTextField searchTextField;
    private JPanel frame;
    private JButton openButton;
    private JButton saveAsButton;
    private JLabel labelTotalRows;

    private DefaultTableModel tableModel;
    private final Vector<String> currentHeaders = new Vector<>();
    private Vector<Vector<String>> dataVectorBeforeFiltering = new Vector<>();

    // Search and filter cache
    private boolean inFilter = false;
    private int searchRowCache = CACHE_INVALID;
    private int searchColCache = CACHE_INVALID;
    private String lastSearchedText;

    // File cache
    private File lastOpenedLocation;

    private void run() {

        // Button Listeners
        openButton.addActionListener(this::onOpenButtonClicked);
        saveAsButton.addActionListener(this::onSaveButtonClicked);
        searchButton.addActionListener(e -> search());
        filterButton.addActionListener(e -> filter());

        //Text Fields Listeners
        searchTextField.addActionListener(e -> search());

        // Main Table Listeners
        mainTable.getTableHeader().addMouseListener(createNewHeaderMouseAdapter());
        mainTable.addMouseListener(createNewColumnMouseAdapter());
        mainTable.getSelectionModel().addListSelectionListener(e -> putTextFromSelectedCellInTopTextField());
        mainTable.getColumnModel().addColumnModelListener(createTableColumnModelListener());

        // UI Preparation
        mainTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        mainTable.setCellSelectionEnabled(true);
        mainTable.getTableHeader().setReorderingAllowed(false);

        setContentPane(frame);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800,800);
        this.setTitle("GigaCsv");

        setVisible(true);
    }

    private MouseListener createNewHeaderMouseAdapter() {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseClicked(e);

                int col = mainTable.columnAtPoint(e.getPoint());

                if (e.getButton() == MOUSE_LEFT_CLICK) {
                    mainTable.clearSelection();
                    mainTable.setColumnSelectionInterval(col, col);
                    mainTable.setRowSelectionInterval(0, tableModel.getRowCount() - 1);
                }

                if (e.getButton() == MOUSE_RIGHT_CLICK) {
                    var popup = createJPopup(() -> deleteColumn(col), () -> "Delete column");
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        };
    }

    private MouseListener createNewColumnMouseAdapter() {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseReleased(e);

                if (e.getButton() == MOUSE_RIGHT_CLICK) {
                    mainTable.setColumnSelectionInterval(0, currentHeaders.size() - 1);
                    var popup = createJPopup(() -> deleteRows(mainTable.getSelectedRows()), () -> inFilter ? "Hide rows" : "Delete rows");
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        };
    }

    public JPopupMenu createJPopup(Runnable r, Supplier<String> text) {
        JPopupMenu popup = new JPopupMenu();

        JMenuItem item = new JMenuItem(new AbstractAction(text.get()) {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });

        popup.add(item);

        return popup;
    }

    private TableColumnModelListener createTableColumnModelListener() {
        return new TableColumnModelListener() {
            @Override
            public void columnAdded(TableColumnModelEvent e) {}

            @Override
            public void columnRemoved(TableColumnModelEvent e) {}

            @Override
            public void columnMoved(TableColumnModelEvent e) {}

            @Override
            public void columnMarginChanged(ChangeEvent e) {}

            @Override
            public void columnSelectionChanged(ListSelectionEvent e) {
                putTextFromSelectedCellInTopTextField();
            }
        };
    }

    private void putTextFromSelectedCellInTopTextField() {
        int row = mainTable.getSelectedRow();
        int col = mainTable.getSelectedColumn();

        if (row >= 0 && col >= 0) {
            cellTextField.setText((String) tableModel.getDataVector().get(row).get(col));
        }
    }

    private void deleteColumn(int index) {
        currentHeaders.remove(index);

        Vector<Vector<String>> dataVector = safe(tableModel.getDataVector().clone());
        for (Vector<String> row : dataVector) {
            row.remove(index);
        }

        tableModel.setDataVector(dataVector, currentHeaders);
        tableModel.fireTableDataChanged();
    }

    private void deleteRows(int[] indexes) {
        Vector<Vector<String>> dataVector = safe(tableModel.getDataVector().clone());

        for (int i = indexes.length - 1; i >= 0; i--) {
            dataVector.remove(indexes[i]);
        }

        tableModel.setDataVector(dataVector, currentHeaders);
        tableModel.fireTableDataChanged();
        updateRowLabel();
    }

    private void search() {
        if (inFilter) {
            inFilter = false;
            tableModel.setDataVector(dataVectorBeforeFiltering, currentHeaders);
            dataVectorBeforeFiltering = null;
            updateRowLabel();
        }

        String textToSearch = searchTextField.getText();
        Vector<Vector<String>> matrix = safe(tableModel.getDataVector());
        final int rowSize = matrix.size();
        final int colSize = matrix.get(0).size();

        if (textToSearch == null || textToSearch.isBlank()) {
            return;
        }

        int rowIdx = 0;
        int colIdxStart = 0;

        // Cache
        if (searchRowCache != CACHE_INVALID) {
            if (textToSearch.equals(lastSearchedText)) {
                if (searchColCache == colSize - 1) {
                    rowIdx = searchRowCache + 1;
                } else {
                    rowIdx = searchRowCache;
                    colIdxStart = searchColCache + 1;
                }
            } else {
                invalidateSearchCache();
            }
        }

        for (; rowIdx < rowSize; rowIdx++) {
            Vector<String> row = matrix.get(rowIdx);

            for (int colIdx = colIdxStart; colIdx < colSize; colIdx++) {
                colIdxStart = 0;
                String cell = row.get(colIdx);
                if (cell.contains(textToSearch)) {
                    focusCell(rowIdx, colIdx);

                    searchRowCache = rowIdx;
                    searchColCache = colIdx;
                    lastSearchedText = textToSearch;

                    return;
                }
            }
        }
    }

    private void filter() {
        if (!inFilter) {
            inFilter = true;
            dataVectorBeforeFiltering = safe(tableModel.getDataVector());
        }

        String textToSearch = searchTextField.getText();

        if (textToSearch == null || textToSearch.isBlank()) {
            tableModel.setDataVector(dataVectorBeforeFiltering, currentHeaders);
            inFilter = false;
            return;
        }

        Vector<Vector<String>> matrix = new Vector<>();
        Vector<Vector<String>> data = safe(dataVectorBeforeFiltering.clone());

        for (Vector<String> row : data) {
            for (int cell = 0; cell < row.size(); cell++) {
                if (row.get(cell).contains(textToSearch)) {
                    matrix.add(row);
                    break;
                }
            }
        }

        tableModel.setDataVector(matrix, currentHeaders);
        updateRowLabel();
    }

    private void updateRowLabel() {
        String size = tableModel.getDataVector().size() + "";

        size = new StringBuilder(size).reverse().toString();
        size = size.replaceAll("(.{3})(?=.)", "$1_");
        size = new StringBuilder(size).reverse().toString();

        labelTotalRows.setText("Total Rows: " + size);
    }

    private void focusCell(int row, int col) {
        mainTable.setColumnSelectionInterval(col, col);
        mainTable.setRowSelectionInterval(row, row);
        mainTable.scrollRectToVisible(mainTable.getCellRect(row, col, true));
    }

    private void invalidateSearchCache() {
        lastSearchedText = null;
        searchRowCache = CACHE_INVALID;
        searchColCache = CACHE_INVALID;
    }

    private List<String[]> createDataForWritingFileAsCsv() {
        var result = new ArrayList<String[]>();
        result.add(toArray(currentHeaders));

        Vector<Vector<String>> data = safe(tableModel.getDataVector());
        data.forEach(vector -> result.add(toArray(vector)));

        return result;
    }

    private void onOpenButtonClicked(ActionEvent e) {
            JFileChooser chooser = createFileChooser();
            chooser.showOpenDialog(MainForm.this);

            File fileToRead = chooser.getSelectedFile();

            if (fileToRead == null) {
                return;
            }

        String[][] csv;
        try {
            csv = PARSER.parseCsv(Files.readString(fileToRead.toPath()));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(MainForm.this, "Could not open: " + fileToRead.getAbsolutePath() + "\n Check file encoding!");
            return;
        }

        lastOpenedLocation = fileToRead;
        currentHeaders.clear();
        currentHeaders.addAll(Arrays.asList(csv[0]));
        tableModel = new DefaultTableModel(csv, csv[0]);
        tableModel.removeRow(0);
        mainTable.setModel(tableModel);

        this.setTitle(fileToRead.getName());

        updateRowLabel();
    }

    private void onSaveButtonClicked(ActionEvent e) {
            JFileChooser chooser = createFileChooser();
            chooser.showSaveDialog(MainForm.this);

            File fileToRead = chooser.getSelectedFile();

            if (fileToRead == null) {
                return;
            }

            if (!fileToRead.getAbsolutePath().endsWith(".csv")) {
                fileToRead = new File(fileToRead.getAbsolutePath() + ".csv");
            }

            try {
                List<String[]> data = createDataForWritingFileAsCsv();
                Files.writeString(fileToRead.toPath(), PARSER.toCsv(data));
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(MainForm.this, "Could not open: " + fileToRead.getAbsolutePath());
                return;
            }

            JOptionPane.showMessageDialog(MainForm.this, "File saved successfully!");
    }

    private JFileChooser createFileChooser() {
        JFileChooser chooser = new JFileChooser();

        chooser.setCurrentDirectory(lastOpenedLocation);
        chooser.setFileFilter(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.getName().endsWith("csv") || f.isDirectory();
            }

            @Override
            public String getDescription() {
                return "csv";
            }
        });

        return chooser;
    }

    private String[] toArray(Vector<String> v) {
        return v.toArray(String[]::new);
    }

    @SuppressWarnings("unchecked")
    private Vector<Vector<String>> safe(Object v) {
        return (Vector<Vector<String>>) v;
    }

    // Run
    public static void main(String[] args) {
        new MainForm().run();
    }
}
