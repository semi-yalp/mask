// 裸 INTERVAL 串的解析期规范化（逐语义移植 Java PgBareIntervalLiterals）：
// '1 day' → INTERVAL '1' DAY；'2 hours' → INTERVAL '0 02:00:00' DAY TO SECOND；
// '1 year 2 mons' → INTERVAL '1-2' YEAR TO MONTH。值等价依赖 PG 的绝对
// 时长语义（1 day=86400s，1 week=7 days，年/月为日历单位）。Calcite 类型
// 系统表达不了的形态保持 fail-closed：跨族混合（'1 year 1 day'）、分数月、
// @/ago 装饰、前导字段 >2 位（Calcite 校验要求精度括号，而 PG 的 interval
// 字面量括号只允许出现在 SECOND 且 p≤6——PG 16 实证）。
package parser

import (
	"math/big"
	"regexp"
	"strings"
)

var (
	intervalNumber = regexp.MustCompile(`^[-+]?(\d+)(?:\.(\d+))?$`)
	intervalClock  = regexp.MustCompile(`^(\d{1,7}):(\d{1,2})(?::(\d{1,2})(?:\.(\d{1,6}))?)?$`)
)

const (
	microsPerSecond = 1_000_000
	microsPerMinute = 60 * microsPerSecond
	microsPerHour   = 60 * microsPerMinute
	microsPerDay    = 24 * microsPerHour
)

var intervalUnitMonths = map[string]int64{
	"y": 12, "yr": 12, "yrs": 12, "year": 12, "years": 12,
	"mon": 1, "mons": 1, "month": 1, "months": 1,
	"decade": 120, "decades": 120,
	"century": 1200, "centuries": 1200,
	"millennium": 12000, "millennia": 12000,
}

var intervalUnitDays = map[string]int64{
	"w": 7, "week": 7, "weeks": 7,
	"d": 1, "day": 1, "days": 1,
}

var intervalUnitMicros = map[string]int64{
	"h": microsPerHour, "hr": microsPerHour, "hrs": microsPerHour,
	"hour": microsPerHour, "hours": microsPerHour,
	"m": microsPerMinute, "min": microsPerMinute, "mins": microsPerMinute,
	"minute": microsPerMinute, "minutes": microsPerMinute,
	"s": microsPerSecond, "sec": microsPerSecond, "secs": microsPerSecond,
	"second": microsPerSecond, "seconds": microsPerSecond,
	"ms": 1_000, "millis": 1_000, "millisecond": 1_000, "milliseconds": 1_000,
	"us": 1, "usec": 1, "micros": 1, "microsecond": 1, "microseconds": 1,
}

// normalizedInterval 是规范化产物：限定词值 + 单位（unitTo 空 = 单字段）。
type normalizedInterval struct {
	Value  string // 已含符号
	Unit   string
	UnitTo string
}

// normalizeBareInterval 规范化裸 PG interval 串；不支持形态返回 false。
// sign 是 INTERVAL 与串之间解析到的符号（+1/-1）。
func normalizeBareInterval(raw string, sign int) (normalizedInterval, bool) {
	text := strings.TrimSpace(raw)
	lower := strings.ToLower(text)
	if text == "" || strings.HasPrefix(text, "@") || strings.HasSuffix(lower, "ago") {
		return normalizedInterval{}, false
	}
	var months, micros int64
	tokens := strings.Fields(text)
	for i := 0; i < len(tokens); {
		token := tokens[i]
		if m := intervalClock.FindStringSubmatch(token); m != nil {
			added, ok := clockMicros(m)
			if !ok {
				return normalizedInterval{}, false
			}
			micros += added
			i++
			continue
		}
		m := intervalNumber.FindStringSubmatch(token)
		if m == nil {
			return normalizedInterval{}, false
		}
		if i+1 >= len(tokens) {
			return normalizedInterval{}, false
		}
		// "<天数> HH:MM[:SS]"：数字是天数，前导符号作用于整值（PG 整串符号语义）
		if cm := intervalClock.FindStringSubmatch(tokens[i+1]); cm != nil {
			if m[2] != "" {
				return normalizedInterval{}, false
			}
			added, ok := clockMicros(cm)
			if !ok {
				return normalizedInterval{}, false
			}
			dayCount, err := parseInt64(m[0])
			if err != nil {
				return normalizedInterval{}, false
			}
			if dayCount < 0 {
				added = -added
			}
			micros += dayCount*microsPerDay + added
			i += 2
			continue
		}
		unit := strings.ToLower(tokens[i+1])
		if mult, ok := intervalUnitMonths[unit]; ok {
			if m[2] != "" {
				return normalizedInterval{}, false
			}
			n, err := parseInt64(m[0])
			if err != nil {
				return normalizedInterval{}, false
			}
			months += n * mult
			i += 2
			continue
		}
		if mult, ok := intervalUnitDays[unit]; ok {
			scaled, ok := scaledIntegral(m[0], mult*int64(microsPerDay))
			if !ok {
				return normalizedInterval{}, false
			}
			micros += scaled
			i += 2
			continue
		}
		if mult, ok := intervalUnitMicros[unit]; ok {
			scaled, ok := scaledIntegral(m[0], mult)
			if !ok {
				return normalizedInterval{}, false
			}
			micros += scaled
			i += 2
			continue
		}
		return normalizedInterval{}, false
	}
	if sign == -1 {
		months = -months
		micros = -micros
	}
	if months != 0 && micros != 0 {
		// PG 允许两族共存于一个 interval；Calcite 类型系统表达不了
		return normalizedInterval{}, false
	}
	if months != 0 {
		return yearMonthLiteral(months)
	}
	return dayTimeLiteral(micros)
}

// scaledIntegral 值×乘积的精确整数（保留 token 符号）；非整数或溢出返回 false。
func scaledIntegral(number string, multiplier int64) (int64, bool) {
	r, ok := new(big.Rat).SetString(number)
	if !ok {
		return 0, false
	}
	r.Mul(r, new(big.Rat).SetInt64(multiplier))
	if !r.IsInt() {
		return 0, false
	}
	num := r.Num()
	if !num.IsInt64() {
		return 0, false
	}
	return num.Int64(), true
}

func parseInt64(s string) (int64, error) {
	var v int64
	neg := false
	i := 0
	if s != "" && (s[0] == '-' || s[0] == '+') {
		neg = s[0] == '-'
		i++
	}
	for ; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return 0, errNotNumber
		}
		if v > (1<<63-1-(int64(s[i]-'0')))/10 {
			return 0, errNotNumber
		}
		v = v*10 + int64(s[i]-'0')
	}
	if neg {
		v = -v
	}
	return v, nil
}

type constError string

func (e constError) Error() string { return string(e) }

const errNotNumber = constError("not a number or out of range")

func clockMicros(groups []string) (int64, bool) {
	hours, err := parseInt64(groups[1])
	if err != nil {
		return 0, false
	}
	minutes, err := parseInt64(groups[2])
	if err != nil {
		return 0, false
	}
	value := hours*int64(microsPerHour) + minutes*int64(microsPerMinute)
	if groups[3] != "" {
		seconds, err := parseInt64(groups[3])
		if err != nil {
			return 0, false
		}
		value += seconds * int64(microsPerSecond)
	}
	if groups[4] != "" {
		frac, err := parseInt64(groups[4])
		if err != nil {
			return 0, false
		}
		for pad := len(groups[4]); pad < 6; pad++ {
			frac *= 10
		}
		value += frac
	}
	return value, true
}

func yearMonthLiteral(totalMonths int64) (normalizedInterval, bool) {
	absolute := totalMonths
	prefix := ""
	if absolute < 0 {
		absolute = -absolute
		prefix = "-"
	}
	years := absolute / 12
	months := absolute % 12
	if months == 0 {
		if !leadFits(years) {
			return normalizedInterval{}, false
		}
		return normalizedInterval{Value: prefix + formatInt(years), Unit: "YEAR"}, true
	}
	if years == 0 {
		return normalizedInterval{Value: prefix + formatInt(months), Unit: "MONTH"}, true
	}
	if !leadFits(years) {
		return normalizedInterval{}, false
	}
	return normalizedInterval{Value: prefix + formatInt(years) + "-" + formatInt(months),
		Unit: "YEAR", UnitTo: "MONTH"}, true
}

func dayTimeLiteral(totalMicros int64) (normalizedInterval, bool) {
	absolute := totalMicros
	prefix := ""
	if absolute < 0 {
		absolute = -absolute
		prefix = "-"
	}
	days := absolute / int64(microsPerDay)
	remainder := absolute % int64(microsPerDay)
	if remainder == 0 {
		if !leadFits(days) {
			return normalizedInterval{}, false
		}
		return normalizedInterval{Value: prefix + formatInt(days), Unit: "DAY"}, true
	}
	if !leadFits(days) {
		return normalizedInterval{}, false
	}
	hours := remainder / int64(microsPerHour)
	minutes := (remainder % int64(microsPerHour)) / int64(microsPerMinute)
	secondAndFraction := remainder % int64(microsPerMinute)
	seconds := secondAndFraction / int64(microsPerSecond)
	fraction := secondAndFraction % int64(microsPerSecond)
	value := prefix + formatInt(days) + " " + two(hours) + ":" + two(minutes) + ":" + two(seconds)
	if fraction > 0 {
		value += "." + pad6(fraction)
	}
	return normalizedInterval{Value: value, Unit: "DAY", UnitTo: "SECOND"}, true
}

// leadFits 前导字段止步 2 位（>99 无两侧可接受的公共表示）。
func leadFits(value int64) bool {
	return len(formatInt(value)) <= 2
}

func two(v int64) string {
	if v < 10 {
		return "0" + formatInt(v)
	}
	return formatInt(v)
}

func pad6(v int64) string {
	s := formatInt(v)
	for len(s) < 6 {
		s = "0" + s
	}
	return s
}

func formatInt(v int64) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	var buf [21]byte
	i := len(buf)
	u := v
	if neg {
		u = -v
	}
	for u > 0 {
		i--
		buf[i] = byte('0' + u%10)
		u /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
