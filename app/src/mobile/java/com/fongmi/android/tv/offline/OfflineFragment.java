package com.fongmi.android.tv.offline;

import android.content.Intent;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.databinding.AdapterOfflineBinding;
import com.fongmi.android.tv.databinding.FragmentOfflineBinding;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.ImgUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.Locale;

public class OfflineFragment extends BaseFragment {
    private FragmentOfflineBinding binding;
    @Override protected ViewBinding getBinding(@NonNull LayoutInflater inflater, ViewGroup container) {
        return binding = FragmentOfflineBinding.inflate(inflater, container, false);
    }
    @Override protected void initView() {
        Adapter adapter = new Adapter();
        binding.list.setAdapter(adapter);
        binding.list.setItemAnimator(null);
        OfflineRepository.get().entries().observe(getViewLifecycleOwner(), entries -> {
            binding.empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.submitList(entries);
        });
    }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
    private class Adapter extends ListAdapter<OfflineRepository.Entry, Holder> {
        Adapter() {
            super(new DiffUtil.ItemCallback<>() {
                @Override public boolean areItemsTheSame(@NonNull OfflineRepository.Entry a, @NonNull OfflineRepository.Entry b) { return a.video().id.equals(b.video().id); }
                @Override public boolean areContentsTheSame(@NonNull OfflineRepository.Entry a, @NonNull OfflineRepository.Entry b) { return false; }
            });
        }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) { return new Holder(AdapterOfflineBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)); }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            OfflineRepository.Entry entry = getItem(position);
            AdapterOfflineBinding row = holder.row;
            OfflineVideo video = entry.video();
            row.title.setText(video.title + " · " + video.episode + "\n" + video.line + (video.quality.isEmpty() ? "" : " · " + video.quality));
            ImgUtil.load(video.title, video.cover, row.cover);
            float percent = entry.download() == null ? -1 : entry.download().getPercentDownloaded();
            long bytes = entry.download() == null ? 0 : entry.download().getBytesDownloaded();
            row.status.setText(OfflineRepository.get().status(entry) + " · " + Formatter.formatFileSize(requireContext(), bytes) + (percent < 0 ? "" : String.format(Locale.getDefault(), " · %.1f%%", percent)));
            row.progress.setIndeterminate(percent < 0 && entry.active());
            row.progress.setProgress(Math.max(0, (int) (percent * 10)));
            row.toggle.setText(entry.active() ? "暂停缓存" : entry.action());
            row.toggle.setVisibility(entry.complete() ? View.GONE : View.VISIBLE);
            row.toggle.setEnabled(!entry.removing());
            row.toggle.setOnClickListener(v -> OfflineRepository.get().toggle(video));
            row.play.setEnabled(entry.download() != null && !entry.removing());
            row.play.setOnClickListener(v -> startActivity(new Intent(requireContext(), OfflinePlayerActivity.class).putExtra("id", video.id)));
            row.source.setOnClickListener(v -> {
                if (!video.config.equals(com.fongmi.android.tv.api.config.VodConfig.getUrl())) {
                    com.fongmi.android.tv.utils.Notify.show("请先切换到该视频的原站源配置");
                    return;
                }
                com.fongmi.android.tv.ui.activity.VideoActivity.start(requireActivity(), video.site, video.vod, video.title, video.cover, video.episode);
            });
            row.delete.setEnabled(!entry.removing());
            row.delete.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext()).setTitle("删除缓存")
                    .setMessage("删除“" + video.title + " · " + video.episode + "”及已缓存的数据？")
                    .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> OfflineRepository.get().remove(entry)).show());
        }
    }
    private static class Holder extends RecyclerView.ViewHolder {
        final AdapterOfflineBinding row;
        Holder(AdapterOfflineBinding row) { super(row.getRoot()); this.row = row; }
    }
}
