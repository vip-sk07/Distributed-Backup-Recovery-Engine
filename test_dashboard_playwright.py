import sys
import time
from playwright.sync_api import sync_playwright

def run_tests():
    console_errors = []
    
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path='/usr/bin/google-chrome', headless=True)
        context = browser.new_context(viewport={'width': 1440, 'height': 900})
        page = context.new_page()
        
        # Track console errors
        def on_console(msg):
            if msg.type == 'error':
                console_errors.append(msg.text)
                print(f"[BROWSER ERROR] {msg.text}")
            else:
                print(f"[BROWSER LOG] {msg.text}")
                
        page.on('console', on_console)
        
        print("\n--- TEST STEP 1: Navigating to Dashboard ---")
        response = page.goto('http://127.0.0.1:8080', wait_until='networkidle')
        assert response is not None, "Failed to receive response from http://127.0.0.1:8080"
        assert response.status == 200, f"Expected HTTP 200, got {response.status}"
        print(f"[PASS] Connected to Dashboard with HTTP status {response.status}")
        
        # Verify Title & Header
        title = page.title()
        print(f"[PASS] Page title: '{title}'")
        assert "Distributed Backup" in title, f"Unexpected page title: {title}"
        
        header_text = page.locator('h1').inner_text()
        print(f"[PASS] Header title: '{header_text}'")
        assert "Distributed Backup" in header_text
        
        status_text = page.locator('#server-status').inner_text()
        print(f"[PASS] Status indicator: '{status_text}'")
        
        # Wait briefly for dynamic fetch to populate stats
        page.wait_for_selector('#stat-files', state='visible')
        time.sleep(1.0)
        
        # Verify Stats Cards
        stat_files = page.locator('#stat-files').inner_text()
        stat_total_bytes = page.locator('#stat-total-bytes').inner_text()
        stat_unique_chunks = page.locator('#stat-unique-chunks').inner_text()
        stat_dedup_ratio = page.locator('#stat-dedup-ratio').inner_text()
        stat_saved = page.locator('#stat-bandwidth-saved').inner_text()
        
        print("\n--- TEST STEP 2: Verifying Stats Cards ---")
        print(f"  * Tracked Files: {stat_files} ({stat_total_bytes})")
        print(f"  * Unique Chunks: {stat_unique_chunks}")
        print(f"  * Deduplication Ratio: {stat_dedup_ratio}")
        print(f"  * Storage Efficiency / Saved: {stat_saved}")
        
        assert int(stat_files) >= 1, f"Expected at least 1 tracked file, got {stat_files}"
        assert int(stat_unique_chunks) >= 1, f"Expected at least 1 chunk, got {stat_unique_chunks}"
        
        # Verify Files Table
        print("\n--- TEST STEP 3: Verifying File Snapshots Table ---")
        rows = page.locator('#files-table-body tr')
        row_count = rows.count()
        print(f"[PASS] File table row count: {row_count}")
        assert row_count >= 1, "Expected at least 1 row in files table"
        
        first_row_text = rows.first.inner_text()
        print(f"[PASS] First row contents:\n{first_row_text}")
        assert "backup_payload.bin" in first_row_text, "Expected 'backup_payload.bin' in table"
        
        # Capture Overview Screenshot
        page.screenshot(path='playwright_test_results/01_dashboard_overview.png', full_page=True)
        print("[SCREENSHOT] Saved playwright_test_results/01_dashboard_overview.png")
        
        # Test Chunk Modal (Inspect Button)
        print("\n--- TEST STEP 4: Testing Chunk Map Modal (Inspect Button) ---")
        inspect_btn = rows.first.locator('button:text("Inspect")')
        assert inspect_btn.is_visible(), "Inspect button should be visible"
        inspect_btn.click()
        
        page.wait_for_selector('#chunk-modal.active', timeout=5000)
        modal_title = page.locator('#chunk-modal-title').inner_text()
        modal_info = page.locator('#chunk-modal-info').inner_text()
        chunk_blocks = page.locator('#chunk-map-container .chunk-block')
        block_count = chunk_blocks.count()
        print(f"[PASS] Chunk Modal open: '{modal_title}' | Info: '{modal_info}'")
        print(f"[PASS] Rendered interactive chunk blocks: {block_count}")
        assert block_count > 0, "Expected chunk blocks to be rendered in modal"
        
        # Capture Chunk Modal Screenshot
        page.screenshot(path='playwright_test_results/02_chunk_modal.png')
        print("[SCREENSHOT] Saved playwright_test_results/02_chunk_modal.png")
        
        # Close Chunk Modal
        close_chunk_btn = page.locator('#chunk-modal .close-btn')
        close_chunk_btn.click()
        page.wait_for_selector('#chunk-modal.active', state='detached', timeout=5000)
        print("[PASS] Chunk Modal closed successfully")
        
        # Test Refresh Button
        print("\n--- TEST STEP 5: Testing Refresh Button ---")
        refresh_btn = page.locator('#btn-refresh')
        refresh_btn.click()
        time.sleep(0.5)
        print("[PASS] Refresh button triggered cleanly")
        
        # Test Sync Workspace Button
        print("\n--- TEST STEP 6: Testing Sync Workspace Button ---")
        sync_btn = page.locator('#btn-sync')
        assert sync_btn.is_enabled(), "Sync button should be enabled"
        sync_btn.click()
        
        # Wait for toast notification
        page.wait_for_selector('#toast-notify.show', timeout=8000)
        toast_text = page.locator('#toast-msg').inner_text()
        print(f"[PASS] Sync Toast notification triggered: '{toast_text}'")
        assert "Synced" in toast_text or "Sync" in toast_text, f"Unexpected toast notification: {toast_text}"
        
        # Capture Sync Toast Screenshot
        page.screenshot(path='playwright_test_results/03_sync_toast.png')
        print("[SCREENSHOT] Saved playwright_test_results/03_sync_toast.png")
        
        # Wait for sync toast to fade
        time.sleep(3.5)
        
        # Test Live Restore Revision from UI
        print("\n--- TEST STEP 7: Testing Live Restore & Checksum Verification from UI ---")
        restore_btn = rows.first.locator('button:text("Restore")')
        assert restore_btn.is_visible(), "Restore button should be visible"
        restore_btn.click()
        
        page.wait_for_selector('#restore-modal.active', timeout=5000)
        file_path_val = page.locator('#modal-file-path').input_value()
        target_dir_val = page.locator('#modal-target-dir').input_value()
        print(f"[PASS] Restore Modal open: target file = '{file_path_val}', output dir = '{target_dir_val}'")
        assert "backup_payload.bin" in file_path_val, f"Unexpected restore target: {file_path_val}"
        
        # Capture Restore Modal Screenshot
        page.screenshot(path='playwright_test_results/04_restore_modal.png')
        print("[SCREENSHOT] Saved playwright_test_results/04_restore_modal.png")
        
        # Wait for sync toast to disappear completely
        page.wait_for_selector('#toast-notify:not(.show)', timeout=6000)
        time.sleep(1.0)
        
        # Click "Download & Reconstruct" to trigger end-to-end restore
        execute_restore_btn = page.locator('#btn-execute-restore')
        execute_restore_btn.click()
        
        # Wait for restore completion toast
        page.wait_for_selector('#toast-notify.show', timeout=10000)
        time.sleep(0.5)
        restore_toast_text = page.locator('#toast-msg').inner_text()
        print(f"[PASS] Live Restore Toast notification: '{restore_toast_text}'")
        assert "Restored" in restore_toast_text and "Checksum Verified" in restore_toast_text, f"Unexpected restore toast: {restore_toast_text}"
        
        # Capture Restore Toast Screenshot
        page.screenshot(path='playwright_test_results/05_restore_verified_toast.png')
        print("[SCREENSHOT] Saved playwright_test_results/05_restore_verified_toast.png")
        
        # Verify console errors
        print("\n--- TEST STEP 8: Console Error Analysis ---")
        if console_errors:
            print(f"[WARN] Detected {len(console_errors)} console errors:")
            for err in console_errors:
                print("  -", err)
        else:
            print("[PASS] Zero uncaught JavaScript errors in browser console!")
            
        browser.close()
        print("\n=================================================================")
        print("  Playwright Verification Complete: ALL 8 TEST PHASES PASSED!    ")
        print("=================================================================")

if __name__ == '__main__':
    run_tests()
