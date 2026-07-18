path = ARGV.fetch(0)
interesting = File.foreach(path).select do |line|
  line.match?(/error:|fatal:|failed|failure|warning:/i)
end.last(160)

if interesting.empty?
  warn "Apple build command failed; detailed output was withheld because it may contain signing metadata"
  exit
end

interesting.each do |line|
  sanitized = line.dup
  sanitized.gsub!(/Apple Distribution:[^\n\"']+/, "[REDACTED SIGNING IDENTITY]")
  sanitized.gsub!(/iPhone Distribution:[^\n\"']+/, "[REDACTED SIGNING IDENTITY]")
  sanitized.gsub!(/\b[A-Fa-f0-9]{40}\b/, "[REDACTED CERTIFICATE HASH]")
  sanitized.gsub!(/\b[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}\b/, "[REDACTED UUID]")
  sanitized.gsub!(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i, "[REDACTED EMAIL]")
  sanitized.gsub!(/AuthKey_[A-Za-z0-9]+\.p8/, "AuthKey_[REDACTED].p8")
  warn sanitized
end
