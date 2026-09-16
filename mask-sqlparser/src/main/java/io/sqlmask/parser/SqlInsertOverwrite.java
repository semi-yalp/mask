package io.sqlmask.parser;

import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

/**
 * INSERT OVERWRITE [TABLE] t …：SqlInsert 子类，仅携带 overwrite 语义；
 * kind 保持 {@link SqlKind#INSERT}，校验/血缘/直通判断按普通 INSERT 走（spec §4.3）。
 */
public class SqlInsertOverwrite extends SqlInsert {

  private static final SqlOperator OPERATOR =
      new SqlSpecialOperator("INSERT_OVERWRITE", SqlKind.INSERT);

  public SqlInsertOverwrite(SqlParserPos pos, SqlNodeList keywords, SqlNode targetTable,
      SqlNode source, SqlNodeList columnList) {
    super(pos, keywords, targetTable, source, columnList);
  }

  @Override public SqlOperator getOperator() {
    return OPERATOR;
  }

  public boolean isOverwrite() {
    return true;
  }

  @Override public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    final SqlWriter.Frame frame = writer.startList(SqlWriter.FrameTypeEnum.SELECT);
    writer.sep("INSERT OVERWRITE TABLE");
    final int opLeft = getOperator().getLeftPrec();
    final int opRight = getOperator().getRightPrec();
    getTargetTable().unparse(writer, opLeft, opRight);
    if (getTargetColumnList() != null) {
      getTargetColumnList().unparse(writer, opLeft, opRight);
    }
    writer.newlineAndIndent();
    getSource().unparse(writer, 0, 0);
    writer.endList(frame);
  }
}
