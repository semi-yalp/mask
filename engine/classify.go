// Package engine 承载与方言无关的语句分类与(后续任务)校验/改写管线。
// 语句分类逐字镜像 Java AbstractCalciteDialectAdapter 的 classify / isQuery /
// unsupported 三方法(见本文件各函数注释);接受的语句面 = SELECT 与
// WITH ... SELECT 查询、INSERT(含 INSERT OVERWRITE)与 Plain CTAS。
package engine

import (
	"io.sqlmask/go/ast"
	"io.sqlmask/go/maskerr"
)

// Classify 按语句种别给出接受/拒绝裁定,镜像 Java classify 的 switch:
//
//	SELECT、INSERT(含 INSERT OVERWRITE——Java 侧 SqlInsertOverwrite 继承
//	  SqlInsert,kind 恒为 INSERT)→ 接受;
//	ORDER_BY → 被包查询体 isQuery(见下)才接受,否则按被包节点的 kind 拒;
//	WITH     → body 同上;
//	CREATE_TABLE → Query 为 nil(纯建表)→ 拒(message 逐字,K=CREATE_TABLE);
//	  变体(非 Plain)→ 按 Task 9 简报裁定在此拒绝(Java 的变体检查位于
//	  composeWriteStatement.checkCreateTableVariant,Go M1 无 compose 阶段,
//	  前置到本函数:错误码一致,message 另述 variant 名);
//	其余(顶层 Values / SetOp——Java 走 default 分支;Java isQuery 不含
//	  VALUES/SetOp,故 SqlOrderBy 包装后的 `VALUES .. ORDER BY` 与
//	  `.. UNION .. ORDER BY` 的可观测结果同为 UNSUPPORTED,见 isQuery)→ 拒。
//
// ordinal 进入 message 前缀 "statement N:"。
func Classify(stmt ast.Statement, ordinal int) error {
	switch s := stmt.(type) {
	case *ast.OrderBy:
		if isQuery(s.Query) {
			return nil
		}
		return unsupported(sqlKindName(s.Query), ordinal)
	case *ast.With:
		if isQuery(s.Body) {
			return nil
		}
		return unsupported(sqlKindName(s.Body), ordinal)
	case *ast.CreateTable:
		if s.Query == nil {
			return unsupported("CREATE_TABLE", ordinal)
		}
		if s.Variant != ast.Plain {
			return unsupportedCreateTableVariant(s.Variant, ordinal)
		}
		return nil
	case *ast.Select, *ast.Insert, *ast.InsertOverwrite:
		return nil
	default:
		return unsupported(sqlKindName(stmt), ordinal)
	}
}

// isQuery 逐字镜像 Java isQuery:仅 SELECT / WITH / ORDER_BY 算 query-like
// ——VALUES 与集合运算不在其中(顶层 SetOp 即使被 SqlOrderBy 包装也拒绝,
// 与 Java `SELECT 1 UNION SELECT 2 ORDER BY 1` → UNSUPPORTED(UNION) 实测一致)。
func isQuery(q ast.Query) bool {
	switch q.(type) {
	case *ast.Select, *ast.With, *ast.OrderBy:
		return true
	}
	return false
}

// unsupported 镜像 Java unsupported 的 message 拼接(逐字):
// "statement N: unsupported statement kind <K>; only SELECT and WITH ...
// SELECT queries are supported in this version",错误码 UNSUPPORTED_STATEMENT。
// <K> 用 Java SqlKind 名(VALUES/UNION/INTERSECT/EXCEPT/CREATE_TABLE 等,
// 见 sqlKindName)。
func unsupported(kind string, ordinal int) error {
	return maskerr.Errorf(maskerr.UnsupportedStatement,
		"statement %d: unsupported statement kind %s; only SELECT and WITH ... SELECT queries are supported in this version",
		ordinal, kind)
}

// unsupportedCreateTableVariant CREATE TABLE 变体拒绝(Task 9 简报裁定:
// message 自拟、含 variant 名;Java 侧对应 checkCreateTableVariant,其文案
// 列举全部变体并带方言名,此处按单个变体名报)。
func unsupportedCreateTableVariant(v ast.CreateTableVariant, ordinal int) error {
	return maskerr.Errorf(maskerr.UnsupportedStatement,
		"statement %d: unsupported CREATE TABLE variant %s; only plain CREATE TABLE ... AS SELECT is supported in this version",
		ordinal, createTableVariantSQLName(v))
}

// createTableVariantSQLName 变体的 SQL 关键字拼写(message 文案用)。
func createTableVariantSQLName(v ast.CreateTableVariant) string {
	switch v {
	case ast.Replace:
		return "REPLACE"
	case ast.Volatile:
		return "VOLATILE"
	case ast.Set:
		return "SET"
	case ast.Multiset:
		return "MULTISET"
	default:
		return v.String()
	}
}

// sqlKindName 把 AST 节点映射为 Java SqlKind 名(仅 message 文案使用)。
func sqlKindName(node ast.Node) string {
	switch n := node.(type) {
	case *ast.Select:
		return "SELECT"
	case *ast.Values:
		return "VALUES"
	case *ast.SetOp:
		switch n.Op {
		case ast.Union:
			return "UNION"
		case ast.Intersect:
			return "INTERSECT"
		case ast.Except:
			return "EXCEPT"
		default:
			return "UNKNOWN"
		}
	case *ast.With:
		return "WITH"
	case *ast.OrderBy:
		return "ORDER_BY"
	case *ast.Insert:
		return "INSERT"
	case *ast.InsertOverwrite:
		return "INSERT"
	case *ast.CreateTable:
		return "CREATE_TABLE"
	default:
		return "UNKNOWN"
	}
}
