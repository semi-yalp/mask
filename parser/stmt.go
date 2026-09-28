package parser

import (
	"strings"

	"io.sqlmask/go/ast"
	"io.sqlmask/go/dialect"
	"io.sqlmask/go/lexer"
	"io.sqlmask/go/maskerr"
)

// 本文件实现语句层(Task 9):ParseStatement 完整入口与 WITH/VALUES/INSERT/
// CREATE TABLE AS/INSERT OVERWRITE 文法(查询五形态的解析在 select.go,经
// ParseQuery 复用;WITH 头与 VALUES 基元也挂进 ParseQuery,使子查询/派生表/
// INSERT 源/CTAS 源自动获得同面能力)。
//
// ParseStatement 分发(镜像 fork SqlStmt() 的备选序):
//
//	SELECT|WITH|VALUES|VALUE|( → ParseQuery(查询五形态 + statement 级限尾)
//	INSERT [OVERWRITE]        → parseInsert / parseInsertOverwrite
//	CREATE ... TABLE          → parseCreate
//	其余未引号关键字(JavaCC keywords 表)→ PARSE_ERROR(认识但不实现)
//	其余 token               → PARSE_ERROR(语句起点不认识)
//
// 语句末尾必须为 EOF(镜像 SqlStmtEof;顶层分号由 split 包先行剥离,输入
// `SELECT 1; SELECT 2` 在 ';' 处报错,与 jar 实测一致)。
//
// 语法面逐条对齐 fork(Parser.jj + babel parserImpls.ftl + maskParserImpls.ftl,
// 全部经 jar 实测校准),关键差异记录于各产生式注释与 task-9 报告:
//   - WITH:QueryOrExpr 的可选 WithList 包住集合运算归约后的查询体
//     (addWith(withList, toTree)),ORDER BY 限尾包在最外(SqlOrderBy 包装,
//     `WITH .. SELECT .. UNION .. ORDER BY` → ORDER_BY(WITH) → Classify nil);
//     RECURSIVE 仅解析记录(决策 2);WITH 体在 Go AST 下必须为 Query(Java
//     允许任意表达式体、由 classify 拒,Go 在解析层拒 → 错误码差异记契约);
//     集合运算操作数位不接受 WITH(fork AddSetOpQuery 只取 LeafQuery,
//     `.. UNION WITH ..` jar 实测拒,Go 同拒——WITH 只挂 ParseQuery 头)。
//   - VALUES:TableConstructor 行构造器 = ( expr {, expr} ) | 裸表达式;
//     VALUE 单数拼写按 Profile.Conformance 门控(镜像 SqlConformanceEnum
//     isValueAllowed:DEFAULT 拒,MYSQL_5/LENIENT 接受,文案逐字)。
//   - INSERT INTO:列清单与括号源按 LOOKAHEAD(2) 区分——「(」后随非保留
//     标识符才是列清单(镜像 ParenthesizedCompoundIdentifierList 只接受
//     CompoundIdentifier,保留字走括号源分支);列名可为多段标识符(jar 实测
//     t (a.b) 接受);源为完整 ParseQuery(SELECT/VALUES/集合运算/限尾包装/
//     WITH 头均可,jar 实测面)。
//   - INSERT OVERWRITE:AllowInsertOverwrite 门控在 OVERWRITE 之后立即检查
//     (门文案逐字);[TABLE] 可选;表名后下一 token 为关键字 PARTITION,或
//     表名本身为未引号 DIRECTORY 且后随字符串字面量时,按 fork 检查点与
//     原文案拒绝(简报决策 10)。
//   - CREATE [OR REPLACE] [MULTISET|SET] [VOLATILE] TABLE [IF NOT EXISTS]
//     name [(col 类型 [NOT NULL] {, ...})] [AS query]:变体词按 fork 产生式
//     位置解析(jar 实测:MULTISET/SET 先于 VOLATILE、REPLACE 必须经 OR 且在
//     TABLE 前),折算进单个 Variant 字段(叠加形态按 REPLACE>VOLATILE>
//     MULTISET>SET 记录——VOLATILE 必须压过 SET,使 compose 变体钩子的裁定
//     与 Java 逐字一致);IF NOT EXISTS 解析记录进 ast.CreateTable.IfNotExists
//     (fix round 1);列清单镜像 fork ColumnWithType:**列必须带类型**——名称
//     解析进 ast.Columns,类型以深度感知跳读消费后丢弃(M3 从 unparse 重组;
//     仅列名无类型 → PARSE_ERROR,jar 实测 `(a, b)` 报于逗号位、`(a)` 报于
//     闭括号位);缺 AS 时 Query=nil(纯建表),交由 engine.Classify 拒。
//   - 认识但未实现的语句首词,按 jar 实测分两路(fix round 1 控制者裁定):
//     Java 能解析、由 classify 抛 UNSUPPORTED_STATEMENT 的首词(UPDATE/
//     DELETE/MERGE/TABLE/SET/DESCRIBE/CALL + postgres 组 BEGIN/COMMIT/
//     ROLLBACK/SHOW/DISCARD,kind 名逐字)→ UNSUPPORTED_STATEMENT(message
//     镜像 classify 格式,ordinal 固定 0,契约只比 stage+code);Java 文法
//     即拒的首词(GRANT/EXPLAIN/ALTER/TRUNCATE 等)→ PARSE_ERROR。两路各带
//     轻量形状护栏过滤 jar 实测为 FAIL 的残片形态(如 `DELETE t`、`SET` 裸、
//     `SET TRANSACTION`、`CALL proc` 缺括号),护栏未命中的走不认识/PARSE_ERROR
//     分路;残余窗口记 task-9 报告。

// ParseStatement 顶层语句入口:分发查询/INSERT/CREATE,认识但不实现的语句
// 首词与不认识的 token 一律 PARSE_ERROR;语句后必须到输入末尾。
func (p *Parser) ParseStatement() (ast.Statement, error) {
	if p.lexErr != nil {
		return nil, p.lexErr
	}
	tok := p.curTok()
	if tok.Kind == lexer.EOF {
		return nil, p.errAt(tok.Pos, "expected statement, found end of input")
	}
	var (
		stmt ast.Statement
		err  error
	)
	switch {
	case p.atKw("SELECT"), p.atKw("WITH"), p.atKw("VALUES"), p.atKw("VALUE"), p.atOp("("):
		// 查询形态(SELECT/VALUES/WITH/括号查询),含 statement 级限尾。
		// 查询体五节点(*ast.Select/Values/SetOp/With/OrderBy)同时实现
		// ast.Statement(ast 包注释约定),断言恒成功。
		q, qerr := p.ParseQuery()
		if qerr != nil {
			return nil, qerr
		}
		s, ok := q.(ast.Statement)
		if !ok {
			return nil, p.errAt(tok.Pos, "query %T is not a statement", q)
		}
		stmt = s
	case p.atKw("INSERT"):
		if p.peekKw("OVERWRITE") {
			stmt, err = p.parseInsertOverwrite()
		} else {
			stmt, err = p.parseInsert()
		}
	case p.atKw("CREATE"):
		stmt, err = p.parseCreate()
	default:
		// 认识但未实现的语句首词(fix round 1 两路,见文件头说明):jar 实测
		// Java 能解析、classify 拒的首词 → UNSUPPORTED_STATEMENT(错误码语义
		// 归 classify 阶段,T10 GoVerdict 按码归类);其余 JavaCC keywords 表内
		// 首词(fork 文法即拒)与不认识 token → PARSE_ERROR。引号标识符永不
		// 匹配关键字(决策 3),走不认识分支。
		if kind, ok := p.unsupportedFirstWordKind(tok); ok {
			return nil, maskerr.Errorf(maskerr.UnsupportedStatement,
				"statement 0: unsupported statement kind %s; only SELECT and WITH ... SELECT queries are supported in this version", kind)
		}
		if tok.Kind == lexer.Ident && javaCCKeyword[strings.ToUpper(tok.Text)] {
			return nil, p.errAt(tok.Pos, "unsupported statement starting with keyword %q", tok.Text)
		}
		return nil, p.errAt(tok.Pos, "unexpected token %s %q at statement start", tok.Kind, tok.Text)
	}
	if err != nil {
		return nil, err
	}
	// 镜像 SqlStmtEof:语句之后必须到输入末尾(残留 token/分号即报错)。
	if t := p.curTok(); t.Kind != lexer.EOF {
		return nil, p.errAt(t.Pos, "unexpected token %q after statement; expected end of input", t.Text)
	}
	return stmt, nil
}

// ---------------------------------------------------------------------------
// WITH / CTE
// ---------------------------------------------------------------------------

// parseWith 解析 WITH [RECURSIVE] name [(cols)] AS ( query ) {, ...} body。
// 镜像 QueryOrExpr:WithList 包住集合运算归约后的查询体(addWith(toTree)),
// 随后的 ORDER BY/LIMIT/OFFSET/FETCH 由 parseOrderAndTail 包在最外(SqlOrderBy
// 包装)。WITH 只在本头位消费——集合运算操作数位(fork LeafQuery)不接受
// WITH,jar 实测 `.. UNION WITH ..` 拒。
func (p *Parser) parseWith() (ast.Query, error) {
	withTok := p.advance() // WITH
	w := &ast.With{Pos: withTok.Pos}
	if p.atKw("RECURSIVE") {
		p.advance()
		w.Recursive = true // 决策 2:仅解析记录
	}
	for {
		item, err := p.parseWithItem()
		if err != nil {
			return nil, err
		}
		w.Items = append(w.Items, item)
		if p.atOp(",") {
			p.advance()
			continue
		}
		break
	}
	body, topFetch, err := p.parseQueryPrimary()
	if err != nil {
		return nil, err
	}
	q, err := p.parseSetOpExpr(body)
	if err != nil {
		return nil, err
	}
	w.Body = q
	return p.parseOrderAndTail(w, topFetch, q != body)
}

// parseWithItem 解析单个 CTE:name(非保留标识符)[(col, ...)] AS ( query )。
// fork AddWithItem:名称/列名为 SimpleIdentifier(保留字不可,按 aliasable
// 判定),列为 ParenthesizedSimpleIdentifierList(至少一个),体为
// ParenthesizedExpression(ACCEPT_QUERY)——括号必需,体内经 ParseQuery 获得
// 完整查询能力(集合运算/限尾留在括号内)。
func (p *Parser) parseWithItem() (ast.WithItem, error) {
	tok := p.curTok()
	if !p.aliasable(tok) {
		return ast.WithItem{}, p.errAt(tok.Pos, "expected CTE name, found %s %q", tok.Kind, tok.Text)
	}
	p.advance()
	item := ast.WithItem{Name: p.simpleIdentFrom(tok)}
	if p.atOp("(") {
		p.advance()
		for {
			ct := p.curTok()
			if !p.aliasable(ct) {
				return ast.WithItem{}, p.errAt(ct.Pos, "expected column name in CTE, found %s %q", ct.Kind, ct.Text)
			}
			p.advance()
			item.Columns = append(item.Columns, p.simpleIdentFrom(ct))
			if p.atOp(",") {
				p.advance()
				continue
			}
			break
		}
		if _, err := p.expectOp(")"); err != nil {
			return ast.WithItem{}, err
		}
	}
	if _, err := p.expectKw("AS"); err != nil {
		return ast.WithItem{}, err
	}
	if _, err := p.expectOp("("); err != nil {
		return ast.WithItem{}, err
	}
	body, err := p.ParseQuery()
	if err != nil {
		return ast.WithItem{}, err
	}
	if _, err := p.expectOp(")"); err != nil {
		return ast.WithItem{}, err
	}
	item.Body = body
	return item, nil
}

// ---------------------------------------------------------------------------
// VALUES
// ---------------------------------------------------------------------------

// parseValues 解析 VALUES 行集(fork TableConstructor):
//
//	VALUES|VALUE 行构造器 {, 行构造器}
//	行构造器 = ( expr {, expr} ) | 裸表达式(单表达式成一行)
//
// VALUE 单数拼写按 Profile.Conformance 门控(镜像 isValueAllowed:DEFAULT
// 拒,MYSQL_5/LENIENT 接受;文案逐字对齐 fork RESOURCE.valueNotAllowed)。
// 解析成功即返回 *ast.Values——拒收(顶层 UNSUPPORTED)交给 engine.Classify,
// 解析层按 Calcite 文法接受(简报对齐规则)。
func (p *Parser) parseValues() (ast.Query, error) {
	tok := p.advance() // VALUES 或 VALUE
	if strings.EqualFold(tok.Text, "VALUE") && p.profile.Conformance == dialect.Default {
		return nil, p.errAt(tok.Pos, "VALUE is not allowed under the current SQL conformance level")
	}
	v := &ast.Values{Pos: tok.Pos}
	for {
		row, err := p.parseValuesRow()
		if err != nil {
			return nil, err
		}
		v.Rows = append(v.Rows, row)
		if p.atOp(",") {
			p.advance()
			continue
		}
		break
	}
	return v, nil
}

// parseValuesRow 解析单个行构造器:括号包裹的表达式清单,或退化为单表达式
// 一行(fork RowConstructor 的裸值分支——"a bare value here is standard SQL
// syntax")。ROW(expr..) 形态不在语句层特判,由裸表达式分支按普通函数调用
// 解析(接受面一致;AST 形态差异记 watchlist);空括号行与 DEFAULT 项不在
// M1 表达式支持面,交由 parseExpr 报 PARSE_ERROR(fork 接受 `VALUES (DEFAULT)`,
// 差异记契约)。
func (p *Parser) parseValuesRow() ([]ast.Expr, error) {
	if p.atOp("(") {
		p.advance()
		var row []ast.Expr
		for {
			e, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			row = append(row, e)
			if p.atOp(",") {
				p.advance()
				continue
			}
			break
		}
		if _, err := p.expectOp(")"); err != nil {
			return nil, err
		}
		return row, nil
	}
	e, err := p.parseExpr()
	if err != nil {
		return nil, err
	}
	return []ast.Expr{e}, nil
}

// ---------------------------------------------------------------------------
// INSERT / INSERT OVERWRITE
// ---------------------------------------------------------------------------

// parseInsert 解析 INSERT INTO target [(cols)] source(fork SqlInsert;INSERT
// 之后的方言关键词清单在该 fork 为空产生式,不消费)。「(」的歧义按
// LOOKAHEAD(2) 区分(见 peekAliasable);源为完整 ParseQuery
// (ORDER BY/LIMIT 留在源内,fork 源位置即 OrderedQueryOrExpr)。
func (p *Parser) parseInsert() (ast.Statement, error) {
	tok := p.advance() // INSERT
	if _, err := p.expectKw("INTO"); err != nil {
		return nil, err
	}
	parts, err := p.parseTableIdentifier()
	if err != nil {
		return nil, err
	}
	ins := &ast.Insert{Pos: tok.Pos, Target: ast.TableNameRef{Pos: parts[0].Pos, Parts: parts}}
	if p.atOp("(") && p.peekAliasable() {
		cols, err := p.parseInsertColumnList()
		if err != nil {
			return nil, err
		}
		ins.Columns = cols
	}
	src, err := p.ParseQuery()
	if err != nil {
		return nil, err
	}
	ins.Source = src
	return ins, nil
}

// peekAliasable 判定当前 token 的下一个 token 可否作「非保留标识符」形态
// (未引号且不在停用词集合):双重用途——INSERT/CREATE 的「(」之后为该形态
// 才作列清单起点(镜像 fork LOOKAHEAD(2) 的 CompoundIdentifier 起始条件:
// 保留字是独立 token 不可作列名;引号标识符恒可),以及语句首词护栏
// (firstWordTailShape)。
func (p *Parser) peekAliasable() bool {
	nxt := p.peekTok()
	if nxt.Kind == lexer.QuotedIdent {
		return true
	}
	return nxt.Kind == lexer.Ident && !aliasStopKw[strings.ToUpper(nxt.Text)]
}

// peek2Tok 返回当前 token 之后的第二个 token(越界时返回末尾 EOF token)。
func (p *Parser) peek2Tok() lexer.Token {
	if p.cur+2 < len(p.toks) {
		return p.toks[p.cur+2]
	}
	return p.toks[len(p.toks)-1]
}

// parseInsertColumnList 解析 ( col {, col} ):列名允许多段(jar 实测
// t (a.b) 接受,CompoundIdentifier);空清单(「()」)与「(a INT)」带类型
// 形态均拒——后者在 fork 受 conformance.allowExtend 门控(五方言均 false)。
func (p *Parser) parseInsertColumnList() ([]ast.Identifier, error) {
	p.advance() // (
	var cols []ast.Identifier
	for {
		ct := p.curTok()
		if !p.aliasable(ct) {
			return nil, p.errAt(ct.Pos, "expected column name in INSERT, found %s %q", ct.Kind, ct.Text)
		}
		id, err := p.parseIdentifier()
		if err != nil {
			return nil, err
		}
		cols = append(cols, id)
		if p.atOp(",") {
			p.advance()
			continue
		}
		break
	}
	if _, err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return cols, nil
}

// parseInsertOverwrite 解析 INSERT OVERWRITE [TABLE] target [(cols)] source
// (fork SqlMaskInsertOverwrite,检查点与文案逐字):
//
//	INSERT OVERWRITE → conformance 门控(AllowInsertOverwrite,hive/sparksql
//	true)→ [TABLE] → 表名 → PARTITION/DIRECTORY 拒 → [(cols)] → 源。
//
// PARTITION:表名后下一 token 为关键字 PARTITION(含 (dt)=.. 形态);DIRECTORY:
// 表名本身为未引号 DIRECTORY(逐字面语义:getToken(0) 为表名末 token)且
// 后随字符串字面量。
func (p *Parser) parseInsertOverwrite() (ast.Statement, error) {
	insTok := p.advance() // INSERT
	p.advance()           // OVERWRITE(门控位置取语句首 token,fork 该错误不携带位点)
	if !p.profile.AllowInsertOverwrite {
		return nil, p.errAt(insTok.Pos, "INSERT OVERWRITE is not enabled for this dialect")
	}
	if p.atKw("TABLE") {
		p.advance()
	}
	parts, err := p.parseTableIdentifier()
	if err != nil {
		return nil, err
	}
	ins := &ast.InsertOverwrite{Pos: insTok.Pos, Target: ast.TableNameRef{Pos: parts[0].Pos, Parts: parts}}
	if p.atKw("PARTITION") {
		t := p.advance()
		return nil, p.errAt(t.Pos, "INSERT OVERWRITE ... PARTITION clause is not supported")
	}
	last := parts[len(parts)-1].Parts[len(parts[len(parts)-1].Parts)-1]
	if !last.Quoted && strings.EqualFold(last.Value, "DIRECTORY") && p.curTok().Kind == lexer.String {
		return nil, p.errAt(p.curTok().Pos, "INSERT OVERWRITE DIRECTORY is not supported")
	}
	if p.atOp("(") && p.peekAliasable() {
		cols, err := p.parseInsertColumnList()
		if err != nil {
			return nil, err
		}
		ins.Columns = cols
	}
	src, err := p.ParseQuery()
	if err != nil {
		return nil, err
	}
	ins.Source = src
	return ins, nil
}

// ---------------------------------------------------------------------------
// CREATE TABLE(CTAS)
// ---------------------------------------------------------------------------

// parseCreate 解析 CREATE [OR REPLACE] [MULTISET|SET] [VOLATILE] TABLE
// [IF NOT EXISTS] name [(col,..)] [AS query](fork SqlCreate → SqlCreateTable;
// 变体词位置 jar 实测:MULTISET/SET 先于 VOLATILE,REPLACE 必须经 OR 且在
// TABLE 之前——`CREATE TABLE REPLACE x` / `CREATE VOLATILE MULTISET TABLE` /
// `CREATE OR REPLACE VOLATILE SET TABLE` 均 jar 实测 FAIL)。变体词折算进
// 单个 Variant 字段(叠加形态按 REPLACE>MULTISET>SET>VOLATILE 记录;Classify
// 对非 Plain 一律拒绝,取舍仅影响报错文案)。CTAS 源为完整 ParseQuery。
func (p *Parser) parseCreate() (ast.Statement, error) {
	tok := p.advance() // CREATE
	replace := false
	if p.atKw("OR") && p.peekKw("REPLACE") {
		p.advance()
		p.advance()
		replace = true
	}
	multiset, set, volatile := false, false, false
	switch {
	case p.atKw("MULTISET"):
		p.advance()
		multiset = true
	case p.atKw("SET"):
		p.advance()
		set = true
	}
	if p.atKw("VOLATILE") {
		p.advance()
		volatile = true
	}
	if _, err := p.expectKw("TABLE"); err != nil {
		return nil, err
	}
	// IF NOT EXISTS:解析记录进 IfNotExists(fork IfNotExistsOpt;fix round 1
	// 控制者裁定新增字段,M3 compose 重组需要)。
	ifNotExists := false
	if p.atKw("IF") {
		p.advance()
		if _, err := p.expectKw("NOT"); err != nil {
			return nil, err
		}
		if _, err := p.expectKw("EXISTS"); err != nil {
			return nil, err
		}
		ifNotExists = true
	}
	parts, err := p.parseTableIdentifier()
	if err != nil {
		return nil, err
	}
	ct := &ast.CreateTable{Pos: tok.Pos, IfNotExists: ifNotExists,
		Name: ast.TableNameRef{Pos: parts[0].Pos, Parts: parts}}
	switch {
	case replace:
		ct.Variant = ast.Replace
	case volatile:
		// 叠加形态的折算优先级(fix round 1 调整):VOLATILE 必须压过
		// MULTISET/SET——compose 变体钩子(Java checkCreateTableVariant)拒
		// VOLATILE 而放行 SET,`CREATE SET VOLATILE TABLE` 折成 Set 会误放行。
		ct.Variant = ast.Volatile
	case multiset:
		ct.Variant = ast.Multiset
	case set:
		ct.Variant = ast.Set
	}
	if p.atOp("(") && p.peekAliasable() {
		cols, err := p.parseCreateColumnList()
		if err != nil {
			return nil, err
		}
		ct.Columns = cols
	}
	if p.atKw("AS") {
		p.advance()
		q, err := p.ParseQuery()
		if err != nil {
			return nil, err
		}
		ct.Query = q
	}
	return ct, nil
}

// parseCreateColumnList 解析 CREATE TABLE 的列清单(fork ExtendColumnList 的
// ColumnWithType,fix round 1):列必须为「名 类型 [NOT NULL]」对——名称解析
// 进 ast.Columns(允许多段,jar 实测 a.b 接受),类型经 skipColumnType 消费后
// 丢弃(M3 从 unparse 重组);仅列名无类型 → PARSE_ERROR。
func (p *Parser) parseCreateColumnList() ([]ast.Identifier, error) {
	p.advance() // (
	var cols []ast.Identifier
	for {
		ct := p.curTok()
		if !p.aliasable(ct) {
			return nil, p.errAt(ct.Pos, "expected column name in CREATE TABLE, found %s %q", ct.Kind, ct.Text)
		}
		id, err := p.parseIdentifier()
		if err != nil {
			return nil, err
		}
		cols = append(cols, id)
		if err := p.skipColumnType(id.Parts[0].Value); err != nil {
			return nil, err
		}
		if p.atOp(",") {
			p.advance()
			continue
		}
		break
	}
	if _, err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return cols, nil
}

// skipColumnType 消费一列的类型 token(fork DataType [NOT NULL];类型文法不
// 落 AST),至逗号或深度 0 的闭括号为止——括号内逗号(如 DECIMAL(5, 2))随
// 深度吞掉。类型缺失(紧跟逗号/闭括号/输入末尾)→ PARSE_ERROR,位置镜像 jar
// 实测:`(a, b)` 报于逗号位、`(a)` 报于闭括号位。跳读不校验类型文法(垃圾
// 类型如 `a FOO BAR` jar 拒、Go 放行,差异记 task-9 报告)。
func (p *Parser) skipColumnType(name string) error {
	if p.atOp(",") || p.atOp(")") || p.curTok().Kind == lexer.EOF {
		t := p.curTok()
		return p.errAt(t.Pos, "expected column type after column %q, found %s %q", name, t.Kind, t.Text)
	}
	depth := 0
	for {
		t := p.curTok()
		if t.Kind == lexer.EOF {
			return p.errAt(t.Pos, "expected ',' or ')' after column type of %q", name)
		}
		if t.Kind == lexer.Op {
			switch t.Text {
			case "(":
				depth++
			case ")":
				if depth == 0 {
					return nil
				}
				depth--
			case ",":
				if depth == 0 {
					return nil
				}
			}
		}
		p.advance()
	}
}

// ---------------------------------------------------------------------------
// 认识但未实现的语句首词(两路分发)
// ---------------------------------------------------------------------------

// firstWordUnsupportedKinds 语句首词 → Java SqlKind 名:jar 实测(Probe3/
// Probe4,五方言一致)这些首词的良构语句能被 fork 解析成功、由 classify 抛
// UNSUPPORTED_STATEMENT,故 Go 直接在入口按 classify 阶段语义报
// UNSUPPORTED_STATEMENT(fix round 1 控制者裁定;错误码语义归 classify 阶段,
// T10 GoVerdict 按码归类;ordinal 固定 0,契约只比 stage+code)。kind 名逐字
// 取 jar 实测(BEGIN/COMMIT/ROLLBACK/SHOW/DISCARD 的 SqlBegin/Commit/Rollback/
// Show/Discard 节点 kind 均为 OTHER)。
var firstWordUnsupportedKinds = map[string]string{
	"UPDATE":   "UPDATE",
	"DELETE":   "DELETE",
	"MERGE":    "MERGE",
	"TABLE":    "EXPLICIT_TABLE",
	"SET":      "SET_OPTION",
	"DESCRIBE": "DESCRIBE_TABLE",
	"CALL":     "PROCEDURE_CALL",
	"BEGIN":    "OTHER",
	"COMMIT":   "OTHER",
	"ROLLBACK": "OTHER",
	"SHOW":     "OTHER",
	"DISCARD":  "OTHER",
}

// unsupportedFirstWordKind 判定语句首词是否命中「Java 能解析、classify 拒」
// 分路;命中返回 Java SqlKind 名。首词后的轻量形状护栏见 firstWordTailShape。
func (p *Parser) unsupportedFirstWordKind(tok lexer.Token) (string, bool) {
	if tok.Kind != lexer.Ident {
		return "", false
	}
	word := strings.ToUpper(tok.Text)
	kind, ok := firstWordUnsupportedKinds[word]
	if !ok || !p.firstWordTailShape(word) {
		return "", false
	}
	return kind, true
}

// firstWordTailShape 首词后的轻量形状护栏:过滤 jar 实测(Probe3/Probe4 与
// T11 补测,五方言一致)为 FAIL 的残片形态,使其回到 PARSE_ERROR 分路——
//
//	DELETE 后必须随 FROM;MERGE 后必须随 INTO;CALL 的名字后必须有括号
//	(或点段的下一段);SHOW 后必须随标识符形态项;DISCARD 仅接受 ALL
//	(T11 jar 实测 `DISCARD x` → PARSE_ERROR、`DISCARD ALL` → UNSUPPORTED);
//	SET 后不得为 TRANSACTION(SET TRANSACTION 为保留字,`SET x[TO|=]..`/
//	长形态放行);BEGIN/COMMIT/ROLLBACK 仅接受裸形态或随 WORK(T11 jar 实测
//	`COMMIT x`/`BEGIN FOO` → PARSE_ERROR,裸词与 WORK 形态 → UNSUPPORTED);
//	其余(UPDATE/TABLE)后必须随非保留标识符。
func (p *Parser) firstWordTailShape(word string) bool {
	switch word {
	case "DELETE":
		return p.peekKw("FROM")
	case "MERGE":
		return p.peekKw("INTO")
	case "CALL":
		if !p.peekAliasable() {
			return false
		}
		nxt2 := p.peek2Tok()
		return nxt2.Kind == lexer.Op && (nxt2.Text == "(" || nxt2.Text == ".")
	case "SET":
		return p.peekAliasable() && !p.peekKw("TRANSACTION")
	case "DESCRIBE":
		// DESCRIBE t / DESCRIBE x.y(DESCRIBE_TABLE)与 DESCRIBE <查询>
		// (jar 实测 kind=EXPLAIN)均解析成功;字符串字面量形态 jar 实测 FAIL。
		return p.peekAliasable() || p.peekKw("SELECT") || p.peekKw("WITH") ||
			p.peekKw("VALUES") || (p.peekTok().Kind == lexer.Op && p.peekTok().Text == "(")
	case "SHOW":
		// 裸形态 jar 实测 FAIL,必须随标识符形态项(SHOW t)。
		return p.peekAliasable()
	case "DISCARD":
		// T11 jar 实测:DISCARD ALL 良构、DISCARD x 为 PARSE_ERROR——仅 ALL。
		return p.peekKw("ALL") && p.tailAtStatementEnd(1)
	case "BEGIN", "COMMIT", "ROLLBACK":
		// T11 jar 实测:裸形态与 WORK/TRANSACTION 形态良构(BEGIN
		// TRANSACTION jar 实测 UNSUPPORTED);其余残片(COMMIT x/BEGIN FOO)
		// 为 PARSE_ERROR。
		if p.tailAtStatementEnd(0) {
			return true
		}
		return p.peekWorkTransKw() && p.tailAtStatementEnd(1)
	default: // UPDATE / TABLE:后必须随非保留标识符
		return p.peekAliasable()
	}
}

// tailAtStatementEnd 报告跳过 n 个 token 后是否到达语句末尾(EOF 或顶层分号;
// 本拆分器已在顶层分号处切开,语句内不会再有分号,EOF 即可)。
func (p *Parser) tailAtStatementEnd(n int) bool {
	idx := p.cur + 1 + n
	if idx >= len(p.toks) {
		idx = len(p.toks) - 1
	}
	return p.toks[idx].Kind == lexer.EOF
}

// peekWorkTransKw 报告当前 token 是否为未引号的 WORK 或 TRANSACTION
// (BEGIN/COMMIT/ROLLBACK 的合法伴随词,jar 实测)。
func (p *Parser) peekWorkTransKw() bool {
	t := p.peekTok()
	if t.Kind != lexer.Ident {
		return false
	}
	return strings.EqualFold(t.Text, "WORK") || strings.EqualFold(t.Text, "TRANSACTION")
}

// ---------------------------------------------------------------------------
// JavaCC 关键字表(语句首词的「认识但不实现」判定)
// ---------------------------------------------------------------------------

// javaCCKeyword 是 JavaCC 词法器 token 表的关键字全集,机械提取自
// testdata/tokens.json 的 keywords(cmd/tokenextract 从 JavaCC
// SqlMaskParserImplConstants 提取),由 stmt_test.go 的
// TestJavaCCKeywordTableLocked 双向锁定。仅用于 ParseStatement 的语句首词
// 分发的兜底路(fix round 1:先查 firstWordUnsupportedKinds 的
// UNSUPPORTED_STATEMENT 分路):表内词 → 「认识但 fork 文法即拒」
// PARSE_ERROR(message 含首词);表外 token → 「不认识」PARSE_ERROR。两路
// 错误码一致,文案区分仅助诊断。
var javaCCKeyword = map[string]bool{
	"A": true, "ABS": true, "ABSENT": true, "ABSOLUTE": true, "ACTION": true, "ADA": true, "ADD": true,
	"ADMIN": true, "AFTER": true, "ALL": true, "ALLOCATE": true, "ALLOW": true, "ALTER": true, "ALWAYS": true,
	"AND": true, "ANTI": true, "ANY": true, "APPLY": true, "ARE": true, "ARRAY": true, "ARRAY_AGG": true,
	"ARRAY_CONCAT_AGG": true, "ARRAY_MAX_CARDINALITY": true, "AS": true, "ASC": true, "ASENSITIVE": true, "ASOF": true, "ASSERTION": true,
	"ASSIGNMENT": true, "ASYMMETRIC": true, "AT": true, "ATOMIC": true, "ATTRIBUTE": true, "ATTRIBUTES": true, "AUTHORIZATION": true,
	"AVG": true, "BEFORE": true, "BEGIN": true, "BEGIN_FRAME": true, "BEGIN_PARTITION": true, "BERNOULLI": true, "BETWEEN": true,
	"BIGINT": true, "BINARY": true, "BIT": true, "BLOB": true, "BOOLEAN": true, "BOTH": true, "BREADTH": true,
	"BY": true, "C": true, "CALL": true, "CALLED": true, "CARDINALITY": true, "CASCADE": true, "CASCADED": true,
	"CASE": true, "CAST": true, "CATALOG": true, "CATALOG_NAME": true, "CEIL": true, "CEILING": true, "CENTURY": true,
	"CHAIN": true, "CHAR": true, "CHARACTER": true, "CHARACTERISTICS": true, "CHARACTERS": true, "CHARACTER_LENGTH": true, "CHARACTER_SET_CATALOG": true,
	"CHARACTER_SET_NAME": true, "CHARACTER_SET_SCHEMA": true, "CHAR_LENGTH": true, "CHECK": true, "CLASSIFIER": true, "CLASS_ORIGIN": true, "CLOB": true,
	"CLOSE": true, "COALESCE": true, "COBOL": true, "COLLATE": true, "COLLATION": true, "COLLATION_CATALOG": true, "COLLATION_NAME": true,
	"COLLATION_SCHEMA": true, "COLLECT": true, "COLUMN": true, "COLUMN_NAME": true, "COMMAND_FUNCTION": true, "COMMAND_FUNCTION_CODE": true, "COMMIT": true,
	"COMMITTED": true, "CONDITION": true, "CONDITIONAL": true, "CONDITION_NUMBER": true, "CONNECT": true, "CONNECTION": true, "CONNECTION_NAME": true,
	"CONSTRAINT": true, "CONSTRAINTS": true, "CONSTRAINT_CATALOG": true, "CONSTRAINT_NAME": true, "CONSTRAINT_SCHEMA": true, "CONSTRUCTOR": true, "CONTAINS": true,
	"CONTAINS_SUBSTR": true, "CONTINUE": true, "CONVERT": true, "CORR": true, "CORRESPONDING": true, "COUNT": true, "COVAR_POP": true,
	"COVAR_SAMP": true, "CREATE": true, "CROSS": true, "CUBE": true, "CUME_DIST": true, "CURRENT": true, "CURRENT_CATALOG": true,
	"CURRENT_DATE": true, "CURRENT_DEFAULT_TRANSFORM_GROUP": true, "CURRENT_PATH": true, "CURRENT_ROLE": true, "CURRENT_ROW": true, "CURRENT_SCHEMA": true, "CURRENT_TIME": true,
	"CURRENT_TIMESTAMP": true, "CURRENT_TRANSFORM_GROUP_FOR_TYPE": true, "CURRENT_USER": true, "CURSOR": true, "CURSOR_NAME": true, "CYCLE": true, "DATA": true,
	"DATABASE": true, "DATE": true, "DATEADD": true, "DATEDIFF": true, "DATEPART": true, "DATETIME": true, "DATETIME_DIFF": true,
	"DATETIME_INTERVAL_CODE": true, "DATETIME_INTERVAL_PRECISION": true, "DATETIME_TRUNC": true, "DATE_DIFF": true, "DATE_PART": true, "DATE_TRUNC": true, "DAY": true,
	"DAYOFWEEK": true, "DAYOFYEAR": true, "DAYS": true, "DEALLOCATE": true, "DEC": true, "DECADE": true, "DECIMAL": true,
	"DECLARE": true, "DEFAULT": true, "DEFAULTS": true, "DEFERRABLE": true, "DEFERRED": true, "DEFINE": true, "DEFINED": true,
	"DEFINER": true, "DEGREE": true, "DELETE": true, "DENSE_RANK": true, "DEPTH": true, "DEREF": true, "DERIVED": true,
	"DESC": true, "DESCRIBE": true, "DESCRIPTION": true, "DESCRIPTOR": true, "DETERMINISTIC": true, "DIAGNOSTICS": true, "DISALLOW": true,
	"DISCARD": true, "DISCONNECT": true, "DISPATCH": true, "DISTINCT": true, "DOMAIN": true, "DOT": true, "DOUBLE": true,
	"DOW": true, "DOY": true, "DROP": true, "DYNAMIC": true, "DYNAMIC_FUNCTION": true, "DYNAMIC_FUNCTION_CODE": true, "EACH": true,
	"ELEMENT": true, "ELSE": true, "EMPTY": true, "ENCODING": true, "END": true, "END_FRAME": true, "END_PARTITION": true,
	"EPOCH": true, "EQUALS": true, "ERROR": true, "ESCAPE": true, "EVERY": true, "EXCEPT": true, "EXCEPTION": true,
	"EXCLUDE": true, "EXCLUDING": true, "EXEC": true, "EXECUTE": true, "EXISTS": true, "EXP": true, "EXPLAIN": true,
	"EXTEND": true, "EXTERNAL": true, "EXTRACT": true, "FALSE": true, "FETCH": true, "FILTER": true, "FINAL": true,
	"FIRST": true, "FIRST_VALUE": true, "FLOAT": true, "FLOOR": true, "FOLLOWING": true, "FOR": true, "FOREIGN": true,
	"FORMAT": true, "FORTRAN": true, "FOUND": true, "FRAC_SECOND": true, "FRAME_ROW": true, "FREE": true, "FRIDAY": true,
	"FROM": true, "FULL": true, "FUNCTION": true, "FUSION": true, "G": true, "GENERAL": true, "GENERATED": true,
	"GEOMETRY": true, "GET": true, "GLOBAL": true, "GO": true, "GOTO": true, "GRANT": true, "GRANTED": true,
	"GROUP": true, "GROUPING": true, "GROUPS": true, "GROUP_CONCAT": true, "HAVING": true, "HIERARCHY": true, "HOLD": true,
	"HOP": true, "HOUR": true, "HOURS": true, "IDENTITY": true, "IF": true, "IGNORE": true, "ILIKE": true,
	"IMMEDIATE": true, "IMMEDIATELY": true, "IMPLEMENTATION": true, "IMPORT": true, "IN": true, "INCLUDE": true, "INCLUDING": true,
	"INCREMENT": true, "INDICATOR": true, "INITIAL": true, "INITIALLY": true, "INNER": true, "INOUT": true, "INPUT": true,
	"INSENSITIVE": true, "INSERT": true, "INSTANCE": true, "INSTANTIABLE": true, "INT": true, "INTEGER": true, "INTERSECT": true,
	"INTERSECTION": true, "INTERVAL": true, "INTO": true, "INVOKER": true, "IS": true, "ISODOW": true, "ISOLATION": true,
	"ISOYEAR": true, "JAVA": true, "JOIN": true, "JSON": true, "JSON_ARRAY": true, "JSON_ARRAYAGG": true, "JSON_EXISTS": true,
	"JSON_OBJECT": true, "JSON_OBJECTAGG": true, "JSON_QUERY": true, "JSON_SCOPE": true, "JSON_VALUE": true, "K": true, "KEY": true,
	"KEY_MEMBER": true, "KEY_TYPE": true, "LABEL": true, "LAG": true, "LANGUAGE": true, "LARGE": true, "LAST": true,
	"LAST_VALUE": true, "LATERAL": true, "LEAD": true, "LEADING": true, "LEFT": true, "LENGTH": true, "LEVEL": true,
	"LIBRARY": true, "LIKE": true, "LIKE_REGEX": true, "LIMIT": true, "LN": true, "LOCAL": true, "LOCALTIME": true,
	"LOCALTIMESTAMP": true, "LOCATOR": true, "LOWER": true, "M": true, "MAP": true, "MATCH": true, "MATCHED": true,
	"MATCHES": true, "MATCH_CONDITION": true, "MATCH_NUMBER": true, "MATCH_RECOGNIZE": true, "MAX": true, "MAXVALUE": true, "MEASURE": true,
	"MEASURES": true, "MEMBER": true, "MERGE": true, "MESSAGE_LENGTH": true, "MESSAGE_OCTET_LENGTH": true, "MESSAGE_TEXT": true, "METHOD": true,
	"MICROSECOND": true, "MILLENNIUM": true, "MILLISECOND": true, "MIN": true, "MINUS": true, "MINUTE": true, "MINUTES": true,
	"MINVALUE": true, "MOD": true, "MODIFIES": true, "MODULE": true, "MONDAY": true, "MONTH": true, "MONTHS": true,
	"MORE": true, "MULTISET": true, "MUMPS": true, "NAME": true, "NAMES": true, "NANOSECOND": true, "NATIONAL": true,
	"NATURAL": true, "NCHAR": true, "NCLOB": true, "NESTING": true, "NEW": true, "NEXT": true, "NO": true,
	"NONE": true, "NORMALIZE": true, "NORMALIZED": true, "NOT": true, "NTH_VALUE": true, "NTILE": true, "NULL": true,
	"NULLABLE": true, "NULLIF": true, "NULLS": true, "NUMBER": true, "NUMERIC": true, "OBJECT": true, "OCCURRENCES_REGEX": true,
	"OCTETS": true, "OCTET_LENGTH": true, "OF": true, "OFFSET": true, "OLD": true, "OMIT": true, "ON": true,
	"ONE": true, "ONLY": true, "OPEN": true, "OPTION": true, "OPTIONS": true, "OR": true, "ORDER": true,
	"ORDERING": true, "ORDINAL": true, "ORDINALITY": true, "OTHERS": true, "OUT": true, "OUTER": true, "OUTPUT": true,
	"OVER": true, "OVERLAPS": true, "OVERLAY": true, "OVERRIDING": true, "OVERWRITE": true, "PAD": true, "PARAMETER": true,
	"PARAMETER_MODE": true, "PARAMETER_NAME": true, "PARAMETER_ORDINAL_POSITION": true, "PARAMETER_SPECIFIC_CATALOG": true, "PARAMETER_SPECIFIC_NAME": true, "PARAMETER_SPECIFIC_SCHEMA": true, "PARTIAL": true,
	"PARTITION": true, "PASCAL": true, "PASSING": true, "PASSTHROUGH": true, "PAST": true, "PATH": true, "PATTERN": true,
	"PER": true, "PERCENT": true, "PERCENTILE_CONT": true, "PERCENTILE_DISC": true, "PERCENT_RANK": true, "PERIOD": true, "PERMUTE": true,
	"PIVOT": true, "PLACING": true, "PLAN": true, "PLANS": true, "PLI": true, "PORTION": true, "POSITION": true,
	"POSITION_REGEX": true, "POWER": true, "PRECEDES": true, "PRECEDING": true, "PRECISION": true, "PREPARE": true, "PRESERVE": true,
	"PREV": true, "PRIMARY": true, "PRIOR": true, "PRIVILEGES": true, "PROCEDURE": true, "PUBLIC": true, "QUALIFY": true,
	"QUARTER": true, "QUARTERS": true, "RANGE": true, "RANK": true, "READ": true, "READS": true, "REAL": true,
	"RECURSIVE": true, "REF": true, "REFERENCES": true, "REFERENCING": true, "REGR_AVGX": true, "REGR_AVGY": true, "REGR_COUNT": true,
	"REGR_INTERCEPT": true, "REGR_SLOPE": true, "REGR_SXX": true, "REGR_SXY": true, "REGR_SYY": true, "RELATIVE": true, "RELEASE": true,
	"REPEATABLE": true, "REPLACE": true, "RESET": true, "RESPECT": true, "RESTART": true, "RESTRICT": true, "RESULT": true,
	"RETURN": true, "RETURNED_CARDINALITY": true, "RETURNED_LENGTH": true, "RETURNED_OCTET_LENGTH": true, "RETURNED_SQLSTATE": true, "RETURNING": true, "RETURNS": true,
	"REVOKE": true, "RIGHT": true, "RLIKE": true, "ROLE": true, "ROLLBACK": true, "ROLLUP": true, "ROUTINE": true,
	"ROUTINE_CATALOG": true, "ROUTINE_NAME": true, "ROUTINE_SCHEMA": true, "ROW": true, "ROWS": true, "ROW_COUNT": true, "ROW_NUMBER": true,
	"RUNNING": true, "SAFE_CAST": true, "SAFE_OFFSET": true, "SAFE_ORDINAL": true, "SATURDAY": true, "SAVEPOINT": true, "SCALAR": true,
	"SCALE": true, "SCHEMA": true, "SCHEMA_NAME": true, "SCOPE": true, "SCOPE_CATALOGS": true, "SCOPE_NAME": true, "SCOPE_SCHEMA": true,
	"SCROLL": true, "SEARCH": true, "SECOND": true, "SECONDS": true, "SECTION": true, "SECURITY": true, "SEED": true,
	"SEEK": true, "SELECT": true, "SELF": true, "SEMI": true, "SENSITIVE": true, "SEPARATOR": true, "SEQUENCE": true,
	"SEQUENCES": true, "SERIALIZABLE": true, "SERVER": true, "SERVER_NAME": true, "SESSION": true, "SESSION_USER": true, "SET": true,
	"SETS": true, "SHOW": true, "SIMILAR": true, "SIMPLE": true, "SIZE": true, "SKIP": true, "SMALLINT": true,
	"SNAPSHOT": true, "SOME": true, "SOURCE": true, "SPACE": true, "SPECIFIC": true, "SPECIFICTYPE": true, "SPECIFIC_NAME": true,
	"SQL": true, "SQLEXCEPTION": true, "SQLSTATE": true, "SQLWARNING": true, "SQL_BIGINT": true, "SQL_BINARY": true, "SQL_BIT": true,
	"SQL_BLOB": true, "SQL_BOOLEAN": true, "SQL_CHAR": true, "SQL_CLOB": true, "SQL_DATE": true, "SQL_DECIMAL": true, "SQL_DOUBLE": true,
	"SQL_FLOAT": true, "SQL_INTEGER": true, "SQL_INTERVAL_DAY": true, "SQL_INTERVAL_DAY_TO_HOUR": true, "SQL_INTERVAL_DAY_TO_MINUTE": true, "SQL_INTERVAL_DAY_TO_SECOND": true, "SQL_INTERVAL_HOUR": true,
	"SQL_INTERVAL_HOUR_TO_MINUTE": true, "SQL_INTERVAL_HOUR_TO_SECOND": true, "SQL_INTERVAL_MINUTE": true, "SQL_INTERVAL_MINUTE_TO_SECOND": true, "SQL_INTERVAL_MONTH": true, "SQL_INTERVAL_SECOND": true, "SQL_INTERVAL_YEAR": true,
	"SQL_INTERVAL_YEAR_TO_MONTH": true, "SQL_LONGVARBINARY": true, "SQL_LONGVARCHAR": true, "SQL_LONGVARNCHAR": true, "SQL_NCHAR": true, "SQL_NCLOB": true, "SQL_NUMERIC": true,
	"SQL_NVARCHAR": true, "SQL_REAL": true, "SQL_SMALLINT": true, "SQL_TIME": true, "SQL_TIMESTAMP": true, "SQL_TINYINT": true, "SQL_TSI_DAY": true,
	"SQL_TSI_FRAC_SECOND": true, "SQL_TSI_HOUR": true, "SQL_TSI_MICROSECOND": true, "SQL_TSI_MINUTE": true, "SQL_TSI_MONTH": true, "SQL_TSI_QUARTER": true, "SQL_TSI_SECOND": true,
	"SQL_TSI_WEEK": true, "SQL_TSI_YEAR": true, "SQL_VARBINARY": true, "SQL_VARCHAR": true, "SQRT": true, "START": true, "STATE": true,
	"STATEMENT": true, "STATIC": true, "STDDEV_POP": true, "STDDEV_SAMP": true, "STREAM": true, "STRING_AGG": true, "STRUCTURE": true,
	"STYLE": true, "SUBCLASS_ORIGIN": true, "SUBMULTISET": true, "SUBSET": true, "SUBSTITUTE": true, "SUBSTRING": true, "SUBSTRING_REGEX": true,
	"SUCCEEDS": true, "SUM": true, "SUNDAY": true, "SYMMETRIC": true, "SYSTEM": true, "SYSTEM_TIME": true, "SYSTEM_USER": true,
	"TABLE": true, "TABLESAMPLE": true, "TABLE_NAME": true, "TEMP": true, "TEMPORARY": true, "THEN": true, "THURSDAY": true,
	"TIES": true, "TIME": true, "TIMESTAMP": true, "TIMESTAMPADD": true, "TIMESTAMPDIFF": true, "TIMESTAMP_DIFF": true, "TIMESTAMP_TRUNC": true,
	"TIMEZONE_HOUR": true, "TIMEZONE_MINUTE": true, "TIME_DIFF": true, "TIME_TRUNC": true, "TINYINT": true, "TO": true, "TOP": true,
	"TOP_LEVEL_COUNT": true, "TRAILING": true, "TRANSACTION": true, "TRANSACTIONS_ACTIVE": true, "TRANSACTIONS_COMMITTED": true, "TRANSACTIONS_ROLLED_BACK": true, "TRANSFORM": true,
	"TRANSFORMS": true, "TRANSLATE": true, "TRANSLATE_REGEX": true, "TRANSLATION": true, "TREAT": true, "TRIGGER": true, "TRIGGER_CATALOG": true,
	"TRIGGER_NAME": true, "TRIGGER_SCHEMA": true, "TRIM": true, "TRIM_ARRAY": true, "TRUE": true, "TRUNCATE": true, "TRY_CAST": true,
	"TUESDAY": true, "TUMBLE": true, "TYPE": true, "UESCAPE": true, "UNBOUNDED": true, "UNCOMMITTED": true, "UNCONDITIONAL": true,
	"UNDER": true, "UNION": true, "UNIQUE": true, "UNKNOWN": true, "UNNAMED": true, "UNNEST": true, "UNPIVOT": true,
	"UNSIGNED": true, "UPDATE": true, "UPPER": true, "UPSERT": true, "USAGE": true, "USER": true, "USER_DEFINED_TYPE_CATALOG": true,
	"USER_DEFINED_TYPE_CODE": true, "USER_DEFINED_TYPE_NAME": true, "USER_DEFINED_TYPE_SCHEMA": true, "USING": true, "UUID": true, "VALUE": true, "VALUES": true,
	"VALUE_OF": true, "VARBINARY": true, "VARCHAR": true, "VARIANT": true, "VARYING": true, "VAR_POP": true, "VAR_SAMP": true,
	"VERSION": true, "VERSIONING": true, "VIEW": true, "VOLATILE": true, "WEDNESDAY": true, "WEEK": true, "WEEKS": true,
	"WHEN": true, "WHENEVER": true, "WHERE": true, "WIDTH_BUCKET": true, "WINDOW": true, "WITH": true, "WITHIN": true,
	"WITHOUT": true, "WORK": true, "WRAPPER": true, "WRITE": true, "XML": true, "YEAR": true, "YEARS": true,
	"ZONE": true,
}
