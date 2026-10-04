"""把「DELIMITER + CREATE PROCEDURE + IF NOT EXISTS + ALTER TABLE」形式的升级脚本
转换成微信云托管 DMC / 任意 SQL 控制台都能直接执行的动态 SQL 形式（PREPARE + EXECUTE）。

原因：DELIMITER 是 mysql 命令行客户端的伪指令，网页版 DMC 不识别，
整段执行会报 Error 1064 ... near 'DELIMITER $$ CREATE PROCEDURE'。

用法：python convert_sql_dialect.py <脚本路径> [--inplace]
不带 --inplace 时只打印转换结果，不写文件。
"""
import io
import re
import sys


def escape(s: str) -> str:
    """把 Python 字符串转成 SQL 单引号字面量（内部单引号成对转义）。"""
    return "'" + s.replace("'", "''") + "'"


def convert(sql_text: str) -> str:
    body_lines = []
    for line in sql_text.split("\n"):
        stripped = line.strip()
        if stripped.upper().startswith("--"):
            body_lines.append("")  # 注释由调用方保留原头部，这里挖空
        else:
            body_lines.append(line)
    body = "\n".join(body_lines)

    # 抽出每个 IF NOT EXISTS ... THEN ALTER TABLE ... ; END IF; 块
    pattern = re.compile(
        r"IF\s+NOT\s+EXISTS\s*\(\s*"
        r"(?:SELECT\s+1\s+FROM\s+(?:INFORMATION_SCHEMA|information_schema)\.COLUMNS\s+"
        r"WHERE\s+TABLE_SCHEMA\s*=\s*DATABASE\(\)\s+AND\s+TABLE_NAME\s*=\s*'(?P<table>[^']+)'\s+"
        r"AND\s+COLUMN_NAME\s*=\s*'(?P<col>[^']+)')"
        r"\s*\)\s*THEN\s*"
        r"(?P<ddl>ALTER\s+TABLE\s+.*?;)"
        r"\s*END\s+IF\s*;",
        re.IGNORECASE | re.DOTALL,
    )

    out = []
    last = 0
    for m in pattern.finditer(body):
        out.append(body[last:m.start()])
        table, col, ddl = m.group("table"), m.group("col"), m.group("ddl")
        ddl = " ".join(ddl.split())  # 压成单行
        # 语句自带的结尾分号要剔除：它会落进字符串字面量里
        ddl = ddl.rstrip().rstrip(";")
        # 动态 SQL 是字符串字面量，里面的单引号必须成对转义
        ddl_literal = escape(ddl)
        alt_literal = escape(f"{table}.{col} already exists")
        out.append(
            f"SET @ddl = (SELECT IF(COUNT(*) = 0,\n"
            f"    {ddl_literal},\n"
            f"    {alt_literal})\n"
            f"    FROM information_schema.COLUMNS\n"
            f"    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '{table}'\n"
            f"      AND COLUMN_NAME = '{col}');\n"
            f"PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;"
        )
        last = m.end()
    out.append(body[last:])

    result = "".join(out)
    # 剥掉残留的存储过程包装（此时应为空操作）
    result = re.sub(r"DROP\s+PROCEDURE[^;]*;", "", result, flags=re.IGNORECASE)
    result = re.sub(r"DELIMITER[^;]*;", "", result, flags=re.IGNORECASE)
    result = re.sub(r"CALL\s+\w+\s*\(\s*\)\s*;", "", result, flags=re.IGNORECASE)
    result = re.sub(r"CREATE\s+PROCEDURE.*?END\s*\$\$", "", result, flags=re.IGNORECASE | re.DOTALL)
    return result


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    inplace = "--inplace" in sys.argv
    if not args:
        print("用法: python convert_sql_dialect.py <脚本路径> [--inplace]")
        sys.exit(1)

    path = args[0]
    src = io.open(path, encoding="utf-8").read()
    # 保留原头部注释块（含"为什么改"的说明）
    header = []
    for line in src.split("\n"):
        if line.strip().startswith("--"):
            header.append(line)
        elif not line.strip():
            if header:
                break
        else:
            break
    header_text = "\n".join(header)

    new_body = convert(src)
    new_body = re.sub(r"\n{3,}", "\n\n", new_body).strip()

    out = header_text + "\n" + new_body + "\n"
    # 校验：不得再有 DELIMITER / CREATE PROCEDURE / CALL
    body_only = "\n".join(l for l in out.split("\n") if not l.strip().startswith("--"))
    for bad in ("DELIMITER", "CREATE PROCEDURE", "CALL "):
        if bad in body_only.upper():
            print(f"警告：转换后仍含 {bad}", file=sys.stderr)

    if inplace:
        io.open(path, "w", encoding="utf-8", newline="\n").write(out)
        print(f"已就地转换: {path}")
        print(f"  ADD COLUMN 组数: {len(re.findall(r'ADD COLUMN', body_only, re.I))}")
        print(f"  PREPARE 组数: {len(re.findall(r'PREPARE stmt', body_only, re.I))}")
    else:
        print(out)


if __name__ == "__main__":
    main()
