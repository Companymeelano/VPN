<?php
/**
 * One-time helpers.
 *   browser:  https://ainetmee.ir/v/admin/hash.php?pass=YOUR-PASSWORD
 *   cli:      php hash.php YOUR-PASSWORD
 * Paste the result into config.local.php as  access.adminPassHash
 * Set a long random access.toolKey too (that is what ?action=selftest|refresh|stats uses).
 */
$pass = PHP_SAPI === 'cli' ? (isset($argv[1]) ? $argv[1] : '') : (isset($_GET['pass']) ? (string) $_GET['pass'] : '');
$toolKey = bin2hex(random_bytes(24));
header('Content-Type: text/plain; charset=utf-8');
if ($pass === '') {
    echo "usage: hash.php?pass=<password>\n";
    exit;
}
echo "access.adminPassHash => " . password_hash($pass, defined('PASSWORD_BCRYPT') ? PASSWORD_BCRYPT : PASSWORD_DEFAULT) . "\n";
echo "access.toolKey       => " . $toolKey . "\n";
echo "\nput both into config.local.php, e.g.\n";
echo "<?php return ['access' => ['adminPassHash' => '<hash above>', 'toolKey' => '<key above>']];\n";
