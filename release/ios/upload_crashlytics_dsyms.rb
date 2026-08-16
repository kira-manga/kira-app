require "fileutils"
require "open3"
require "timeout"

SUCCESS_MARKER = "Successfully uploaded Crashlytics symbols"
DEFAULT_TIMEOUT_SECONDS = 300

upload_tool = ARGV.fetch(0)
firebase_plist = ARGV.fetch(1)
dsym_input = ARGV.fetch(2)
marker_path = ENV["CRASHLYTICS_DSYM_UPLOAD_MARKER"].to_s
timeout_seconds = Integer(
  ENV.fetch("CRASHLYTICS_DSYM_UPLOAD_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS.to_s),
  exception: false
)

abort("Crashlytics upload timeout must be a positive integer") unless timeout_seconds&.positive?
abort("Crashlytics upload tool is unavailable") unless File.executable?(upload_tool)
abort("Firebase configuration is unavailable") unless File.file?(firebase_plist)
abort("dSYM input is unavailable") unless File.directory?(dsym_input)

dsym_paths = if File.extname(dsym_input) == ".dSYM"
  [dsym_input]
else
  Dir.glob(File.join(dsym_input, "*.dSYM")).select { |path| File.directory?(path) }.sort
end
abort("No dSYM bundles were produced") if dsym_paths.empty?

dsym_paths.each do |path|
  uuid_output, _uuid_error, uuid_status = Open3.capture3("dwarfdump", "--uuid", path)
  abort("A dSYM bundle has no inspectable UUID") unless uuid_status.success? && uuid_output.include?("UUID:")
end

provider_output = +""
upload_status = nil
upload_pid = nil
timed_out = false

begin
  Open3.popen2e(
    upload_tool,
    "-gsp", firebase_plist,
    "-p", "ios",
    *dsym_paths,
    pgroup: true
  ) do |stdin, output, wait_thread|
    stdin.close
    upload_pid = wait_thread.pid
    begin
      Timeout.timeout(timeout_seconds) do
        provider_output << output.read
        upload_status = wait_thread.value
      end
    rescue Timeout::Error
      timed_out = true
      begin
        Process.kill("TERM", -upload_pid)
      rescue Errno::ESRCH
        # The uploader exited at the timeout boundary.
      end
      sleep(1)
      if wait_thread.alive?
        begin
          Process.kill("KILL", -upload_pid)
        rescue Errno::ESRCH
          # The uploader exited after TERM.
        end
      end
      wait_thread.value
    ensure
      output.close unless output.closed?
    end
  end

  abort("Crashlytics dSYM upload timed out after #{timeout_seconds} seconds") if timed_out
  confirmed = upload_status&.success? && provider_output.include?(SUCCESS_MARKER)
  abort("Crashlytics dSYM upload was not confirmed") unless confirmed

  unless marker_path.empty?
    FileUtils.mkdir_p(File.dirname(marker_path), mode: 0o700)
    File.write(marker_path, "confirmed\n")
    File.chmod(0o600, marker_path)
  end

  puts "Crashlytics dSYM upload confirmed for #{dsym_paths.length} symbol bundle(s)"
ensure
  provider_output.clear
end
