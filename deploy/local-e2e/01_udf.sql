-- 脱敏 UDF（sql-mask 改写输出会调用；语义按行业惯例定义，NULL 入 NULL 出）
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- mask_phone(v, front, back): 保留前 front 后 back 位，中间等长打 *
-- mask_phone('13812345678', 3, 4) => '138****5678'
CREATE OR REPLACE FUNCTION mask_phone(v text, front integer, back integer)
RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE n integer := length(v);
BEGIN
  IF n <= front + back THEN
    RETURN repeat('*', n);
  END IF;
  RETURN substr(v, 1, front) || repeat('*', n - front - back) || substr(v, n - back + 1, back);
END $$;

-- mask_email(v): 保留首字符与 @ 及之后域名，本地部分其余打 *
-- mask_email('zhangsan@qq.com') => 'z***@qq.com'
CREATE OR REPLACE FUNCTION mask_email(v text)
RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE pos integer := position('@' in v);
BEGIN
  IF pos = 0 THEN
    RETURN repeat('*', length(v));
  END IF;
  RETURN substr(v, 1, 1) || '***' || substr(v, pos);
END $$;

-- mask_name(v): 保留姓与名末字，中间打 *
-- mask_name('张三丰') => '张*丰'
CREATE OR REPLACE FUNCTION mask_name(v text)
RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE n integer := length(v);
BEGIN
  IF n <= 1 THEN
    RETURN repeat('*', n);
  END IF;
  RETURN substr(v, 1, 1) || repeat('*', n - 2) || substr(v, n, 1);
END $$;

-- mask_idcard(v, keep): 保留前 6 位（行政区划）与后 keep 位
CREATE OR REPLACE FUNCTION mask_idcard(v text, keep integer DEFAULT 4)
RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE n integer := length(v);
BEGIN
  IF n <= 6 + keep THEN
    RETURN repeat('*', n);
  END IF;
  RETURN substr(v, 1, 6) || repeat('*', n - 6 - keep) || substr(v, n - keep + 1, keep);
END $$;

-- mask_hash(v, algo): 摘要哈希（第二参算法名，如 'sha256'）
CREATE OR REPLACE FUNCTION mask_hash(v text, algo text DEFAULT 'sha256')
RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT encode(digest(v, algo), 'hex')
$$;

-- mask_text(v): 等长全打 *
CREATE OR REPLACE FUNCTION mask_text(v text)
RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT repeat('*', length(v))
$$;

-- mask_custom(v): 自定义形态（哈希前 8 位加标记）
CREATE OR REPLACE FUNCTION mask_custom(v text)
RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT '***' || substr(md5(v), 1, 8)
$$;
