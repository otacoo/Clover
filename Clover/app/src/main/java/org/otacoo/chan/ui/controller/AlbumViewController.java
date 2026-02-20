/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.otacoo.chan.ui.controller;

import static org.otacoo.chan.utils.AndroidUtils.dp;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.core.model.PostImage;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.ui.cell.AlbumViewCell;
import org.otacoo.chan.ui.toolbar.ToolbarMenuSubItem;
import org.otacoo.chan.ui.view.GridRecyclerView;
import org.otacoo.chan.ui.view.PostImageThumbnailView;
import org.otacoo.chan.ui.view.ThumbnailView;

import java.util.List;

public class AlbumViewController extends Controller implements
        ImageViewerController.ImageViewerCallback,
        ImageViewerController.GoPostCallback {
    private GridRecyclerView recyclerView;
    private GridLayoutManager gridLayoutManager;

    private List<PostImage> postImages;
    private int targetIndex = -1;

    private AlbumAdapter albumAdapter;
    private Loadable loadable;

    public AlbumViewController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Navigation
        navigation.buildMenu().withOverflow()
                .withSubItem(R.string.action_download_album, this::downloadAlbumClicked)
                .build().build();

        // View setup
        view = inflateRes(R.layout.controller_album_view);
        recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setHasFixedSize(true);
        gridLayoutManager = new GridLayoutManager(context, 3);
        recyclerView.setLayoutManager(gridLayoutManager);
        recyclerView.setHasFixedSize(true);
        recyclerView.setSpanWidth(dp(120));
        recyclerView.setItemAnimator(null);
        albumAdapter = new AlbumAdapter();
        recyclerView.setAdapter(albumAdapter);
        recyclerView.scrollToPosition(targetIndex);
    }

    public void setImages(Loadable loadable, List<PostImage> postImages, int index, String title) {
        this.loadable = loadable;
        this.postImages = postImages;
        navigation.title = title;
        navigation.subtitle = context.getResources().getQuantityString(R.plurals.image, postImages.size(), postImages.size());
        targetIndex = index;
    }

    private void downloadAlbumClicked(ToolbarMenuSubItem item) {
        AlbumDownloadController albumDownloadController = new AlbumDownloadController(context);
        albumDownloadController.setPostImages(loadable, postImages);
        navigationController.pushController(albumDownloadController);
    }

    @Override
    public ThumbnailView getPreviewImageTransitionView(ImageViewerController imageViewerController, PostImage postImage) {
        AlbumViewCell cell = findCellForImage(postImage);
        if (cell != null) {
            return cell.getThumbnailView();
        }
        return null;
    }

    @Override
    public void onPreviewCreate(ImageViewerController imageViewerController, PostImage postImage) {
    }

    @Override
    public void onBeforePreviewDestroy(ImageViewerController imageViewerController, PostImage postImage) {
        AlbumViewCell cell = findCellForImage(postImage);
        if (cell != null) {
            cell.hideLabel();
        }
    }

    @Override
    public void onPreviewDestroy(ImageViewerController imageViewerController, PostImage postImage) {
        AlbumViewCell cell = findCellForImage(postImage);
        if (cell != null) {
            cell.showLabel();
        }
    }

    private AlbumViewCell findCellForImage(PostImage postImage) {
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View view = recyclerView.getChildAt(i);
            if (view instanceof AlbumViewCell) {
                AlbumViewCell cell = (AlbumViewCell) view;
                if (postImage == cell.getPostImage()) {
                    return cell;
                }
            }
        }
        return null;
    }

    @Override
    public void scrollToImage(PostImage postImage) {
        int index = postImages.indexOf(postImage);
        recyclerView.smoothScrollToPosition(index);
    }

    @Override
    public ImageViewerController.ImageViewerCallback goToPost(PostImage postImage) {
        ThreadController threadController = null;

        if (previousSiblingController instanceof ThreadController) {
            threadController = (ThreadController) previousSiblingController;
        } else if (previousSiblingController instanceof DoubleNavigationController) {
            DoubleNavigationController doubleNav = (DoubleNavigationController) previousSiblingController;
            if (doubleNav.getRightController() instanceof ThreadController) {
                threadController = (ThreadController) doubleNav.getRightController();
            }
        } else if (previousSiblingController == null) {
            //split nav has no "sibling" to look at, so we go WAY back to find the view thread controller
            SplitNavigationController splitNav = (SplitNavigationController) this.parentController.parentController.presentedByController;
            threadController = (ThreadController) splitNav.rightController.childControllers.get(0);
            threadController.selectPostImage(postImage);
            //clear the popup here because split nav is weirdly laid out in the stack
            splitNav.popController();
            return threadController;
        }

        if (threadController != null) {
            threadController.selectPostImage(postImage);
            navigationController.popController(false);
            return threadController;
        } else {
            return null;
        }
    }

    private void openImage(AlbumItemCellHolder albumItemCellHolder, PostImage postImage) {
        // Just ignore the showImages request when the image is not loaded
        if (albumItemCellHolder.thumbnailView.getBitmap() != null) {
            final ImageViewerNavigationController imageViewerNavigationController = new ImageViewerNavigationController(context);
            int index = postImages.indexOf(postImage);
            presentController(imageViewerNavigationController, false);
            imageViewerNavigationController.showImages(postImages, index, loadable, this, this);
        }
    }

    private class AlbumAdapter extends RecyclerView.Adapter<AlbumItemCellHolder> {
        public AlbumAdapter() {
            setHasStableIds(true);
        }

        @Override
        public AlbumItemCellHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new AlbumItemCellHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_album_view, parent, false));
        }

        @Override
        public void onBindViewHolder(AlbumItemCellHolder holder, int position) {
            PostImage postImage = postImages.get(position);
            holder.cell.setPostImage(postImage);
        }

        @Override
        public int getItemCount() {
            return postImages.size();
        }

        @Override
        public long getItemId(int position) {
            return position;
        }
    }

    private class AlbumItemCellHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        private AlbumViewCell cell;
        private PostImageThumbnailView thumbnailView;

        public AlbumItemCellHolder(View itemView) {
            super(itemView);
            cell = (AlbumViewCell) itemView;
            thumbnailView = itemView.findViewById(R.id.thumbnail_view);
            thumbnailView.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            int adapterPosition = getBindingAdapterPosition();
            PostImage postImage = postImages.get(adapterPosition);
            openImage(this, postImage);
        }
    }
}
