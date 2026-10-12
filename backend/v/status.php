<?php
/**
 * The shareable service page: https://ainetmee.ir/v/status.php
 *
 * Same bootstrap as the router (it owns the require order and the config merge), asked politely for its
 * HTML rendering. That is the entire file on purpose: a second place that "knows how to boot the backend"
 * is a second place that can rot. ?r=1 skips the two-minute cache.
 */
$_GET['action'] = 'status';
if (!isset($_GET['html'])) {
    $_GET['html'] = '1';
}
require __DIR__ . '/index.php';
